package com.aitsuki.plugin

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

public class PreDexTransform extends Transform {

    Project project
    public PreDexTransform(Project project) {
        this.project = project
        // 获取到hack module的debug目录，也就是Antilazy.class所在的目录
        def libPath = project.project(":$Configure.hackModuleName").buildDir.absolutePath.concat("\\intermediates\\classes\\debug")
        Inject.appendClassPath(libPath)
        Inject.appendClassPath(Configure.androidJar)
        Inject.appendClassPath(Configure.apacheJar)
    }

    @Override
    String getName() {
        return "preDex"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(Context context, Collection<TransformInput> inputs,
                   Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider, boolean isIncremental)
            throws IOException, TransformException, InterruptedException {


        // 清除备份文件夹
        File backupDir = new File(project.buildDir,"backup")
        if(backupDir.exists()) {
            FileUtils.cleanDirectory(backupDir)
        }

        // 遍历transfrom的inputs
        // inputs有两种类型，一种是目录，一种是jar，需要分别遍历。
        inputs.each {TransformInput input ->
            input.directoryInputs.each {DirectoryInput directoryInput->

                // 这是transfrom的输出目录
                def dest = outputProvider.getContentLocation(directoryInput.name,
                        directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)

                // 备份dir
                def dirBackup = dest.absolutePath.replace('intermediates','backup')
                File dirBackupFile = new File(dirBackup)
                if(!dirBackupFile.exists()) {
                    dirBackupFile.mkdirs()
                }
                FileUtils.copyDirectory(directoryInput.file, dirBackupFile)

                //TODO 注入代码
                Inject.injectDir(directoryInput.file.absolutePath)
                // 将input的目录复制到output指定目录
                FileUtils.copyDirectory(directoryInput.file, dest)
            }

            input.jarInputs.each {JarInput jarInput->

                // 重命名输出文件（同目录copyFile会冲突）
                def jarName = jarInput.name
                def md5Name = DigestUtils.md5Hex(jarInput.file.getAbsolutePath())
                if(jarName.endsWith(".jar")) {
                    jarName = jarName.substring(0,jarName.length()-4)
                }
                def dest = outputProvider.getContentLocation(jarName+md5Name, jarInput.contentTypes, jarInput.scopes, Format.JAR)

                // 备份jar
                def jarBackup = dest.absolutePath.replace('intermediates','backup').replace(jarName,jarName+md5Name)
                File jarBackupFile = new File(jarBackup)
                FileUtils.copyFile(jarInput.file,jarBackupFile)

                //TODO 注入代码
                String jarPath = jarInput.file.absolutePath;
                String projectName = project.rootProject.name;
                if(jarPath.endsWith("classes.jar") && jarPath.contains("exploded-aar\\"+projectName)) {

                    // 排除不需要注入的module
                    def flag  = true
                    Configure.noInjectModules.each {
                        if(jarPath.contains("exploded-aar\\$projectName\\$it")) {
                            flag = false
                        }
                    }

                    if(flag) {
                        Inject.injectJar(jarPath)
                    }
                }

                FileUtils.copyFile(jarInput.file, dest)
            }
        }

        // 生成md5, 因为做了备份，可以直接读取备份生成
        // 首先需要判断是否是release版本，只有在release版本的时候需要生成md5
        File releaseDir = new File(backupDir,"transforms\\${getName()}\\release")
        if(releaseDir.exists()) {
            // 创建patch目录, 用来保存MD5文件
            File patchDir = new File("$project.projectDir.absolutePath\\patch")
            if(!patchDir.exists()) {
                patchDir.mkdirs()
            }

            // 创建md5文件
            File md5File = new File(patchDir, "classesMD5.txt")
            if(md5File.exists()) {
                md5File.delete()
            }

            def pw = md5File.newPrintWriter()

            // 遍历所有class，获取md5，获取完整类名，写入到classesMd5文件中
            releaseDir.eachFileRecurse {File file->
                String filePath = file.getAbsolutePath()

                if(filePath.endsWith('.class') && Inject.needInject(filePath)) {
                    int beginIndex = filePath.lastIndexOf('release')+8
                    String className = filePath.substring(beginIndex, filePath.length()-6).replace('\\','.').replace('/','.')
                    InputStream inputStream = new FileInputStream(file)
                    String md5 = DigestUtils.md5Hex(inputStream)
                    inputStream.close()
                    pw.println("$className-$md5")
                }

                if(filePath.endsWith('.jar')) {
                    File destDir = new File(file.parent,file.getName().replace('.jar',''))
                    JarZipUtil.unzipJar(filePath,destDir.absolutePath)
                    destDir.eachFileRecurse {File f->
                        String fPath =  f.absolutePath
                        if(fPath.endsWith('.class') && Inject.needInject(fPath)) {
                            int beginIndex = fPath.indexOf(destDir.name)+ destDir.name.length()+1
                            String className = fPath.substring(beginIndex, fPath.length()-6).replace('\\','.').replace('/','.')
                            InputStream inputStream= new FileInputStream(f)
                            String md5 = DigestUtils.md5Hex(inputStream)
                            inputStream.close()
                            pw.println("$className-$md5")
                        }
                    }
                    FileUtils.deleteDirectory(destDir)
                }
            }
            pw.close()
        }

        // -------------自动生成补丁包-----------------
        // 如果运行dopatch变体的话，代表我们需要自动生成补丁了
        File dopatchDir = new File(backupDir,"transforms\\${getName()}\\dopatch")
        // 这个是我们release版本打包时保存的md5文件
        File md5File = new File("$project.projectDir\\patch\\classesMD5.txt")
        if(dopatchDir.exists() && md5File.exists()) {
            // 这个是保存补丁的目录
            File patchCacheDir = new File(Configure.patchCacheDir)
            if(patchCacheDir.exists()) {
                FileUtils.cleanDirectory(patchCacheDir)
            } else {
                patchCacheDir.mkdirs()
            }

            // 使用reader读取md5文件，将每一行保存到集合中
            def reader = md5File.newReader()
            List<String> list = reader.readLines()
            reader.close()

            // 遍历当前的所有class文件，再次生成md5
            dopatchDir.eachFileRecurse {File file->
                String filePath = file.getAbsolutePath()
                if(filePath.endsWith('.class') && Inject.needInject(filePath)) {
                    int beginIndex = filePath.lastIndexOf('dopatch')+8
                    String className = filePath.substring(beginIndex, filePath.length()-6).replace('\\','.').replace('/','.')
                    InputStream inputStream = new FileInputStream(file)
                    String md5 = DigestUtils.md5Hex(inputStream)
                    inputStream.close()
                    String str = className +"-"+md5

                    // 然后和release中的md5进行对比，如果不一致，代表这个类已经修改，复制到补丁文件夹中
                    if(!list.contains(str)) {
                        String classFilePath = className.replace('.','\\').concat('.class')
                        File classFile = new File(patchCacheDir,classFilePath)
                        FileUtils.copyFile(file,classFile)
                    }
                }

                // jar包需要先解压，(⊙o⊙)…有很多重复代码，不管了，下次重构再抽取。
                if(filePath.endsWith('.jar')) {
                    File destDir = new File(file.parent,file.getName().replace('.jar',''))
                    JarZipUtil.unzipJar(filePath,destDir.absolutePath)
                    destDir.eachFileRecurse {File f->
                        String fPath =  f.absolutePath
                        if(fPath.endsWith('.class') && Inject.needInject(fPath)) {
                            int beginIndex = fPath.indexOf(destDir.name)+ destDir.name.length()+1
                            String className = fPath.substring(beginIndex, fPath.length()-6).replace('\\','.').replace('/','.')
                            InputStream inputStream= new FileInputStream(f)
                            String md5 = DigestUtils.md5Hex(inputStream)
                            inputStream.close()
                            String str = className+"-"+md5
                            if(!list.contains(str)) {
                                String classFilePath = className.replace('.','\\').concat('.class')
                                File classFile = new File(patchCacheDir,classFilePath)
                                FileUtils.copyFile(file,classFile)
                            }
                        }
                    }
                    FileUtils.deleteDirectory(destDir)
                }
            }
        }
    }
}