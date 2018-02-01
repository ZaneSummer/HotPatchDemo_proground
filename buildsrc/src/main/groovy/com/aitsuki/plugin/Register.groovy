package com.aitsuki.plugin

import com.android.build.gradle.AppExtension
import javassist.ClassPath
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.mortbay.log.Log

/**
 * Created by AItsuki on 2016/4/8.
 *
 */
public class Register implements Plugin<Project> {
    @Override
    public void apply(Project project) {

        def android = project.extensions.findByType(AppExtension)
        PreDexTransform preDexTransform = new PreDexTransform(project)
        android.registerTransform(preDexTransform)

        /**
         * 我们是在混淆之前就完成注入代码的，这会出现问题，找不到AntilazyLoad这个类
         *
         * 我的解决方式：
         * 在PreDexTransform注入代码之前，先将原来没有注入的代码保存了一份到 buildDir/backup
         * 如果开启了混淆，则在混淆之前将代码覆盖回来
         */
        project.afterEvaluate {
            project.android.applicationVariants.each {variant->
                def proguardTask = project.getTasks().findByName("transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}")
                if(proguardTask) {

                    // 如果有混淆，执行之前将备份的文件覆盖原来的文件(变相的清除已注入代码)
                    proguardTask.doFirst {
                        // 如果我们运行的是debug混淆打包，那么备份的dir也是叫“debug”
                        // 如果运行的是release，那么这是“release”
                        File backupDir = new File(project.buildDir,"backup\\transforms\\$preDexTransform.name\\$variant.name")
                        if(backupDir.exists()) {
                            def srcDirPath = backupDir.getAbsolutePath().replace('backup','intermediates')
                            File srcDir = new File(srcDirPath)
                            FileUtils.cleanDirectory(srcDir)
                            FileUtils.copyDirectory(backupDir,srcDir)
                        }
                    }

                    proguardTask.doLast {

                        // 如果是开启混淆的release，混淆注入代码，并且将mapping复制到patch目录
                        if(proguardTask.name.endsWith('ForRelease')) {
                            project.logger.error "0=============="
                            // 遍历proguard文件夹,注入代码
                            File proguardDir = new File("$project.buildDir\\intermediates\\transforms\\proguard\\release")
                            proguardDir.eachFileRecurse { File file ->
                                if(file.name.endsWith('jar')) {
                                    project.logger.error "0=00000============="
                                    Inject.injectJar(file.absolutePath)
                                    project.logger.error "0=11111============="
                                }
                            }

                            project.logger.error "1=============="
                            File mapping = new File("$project.buildDir\\outputs\\mapping\\release\\mapping.txt")
                            File mappingCopy = new File("$project.projectDir\\patch\\mapping.txt")
                            project.logger.error "2=============="
                            FileUtils.copyFile(mapping, mappingCopy)
                        }

                        // 自动打补丁
                        if(proguardTask.name.endsWith('ForDopatch')) {

                            // 解析mapping文件
                            File mapping = new File("$project.projectDir\\patch\\mapping.txt")
                            def reader = mapping.newReader()
                            Map<String, String> map = new HashMap<>()
                            reader.eachLine {String line->
                                if(line.endsWith(':')) {
                                    String[] strings = line.replace(':','').split(' -> ')
                                    if(strings.length == 2) {
                                        map.put(strings[0],strings[1])
                                    }
                                }
                            }
                            reader.close()
                            println "map= $map"

                            // 在Transfrom中已经将需要打补丁的类复制到了指定目录, 我们需要遍历这个目录获取类名
                            List<String> patchList = new ArrayList<>()
                            File patchCacheDir = new File(Configure.patchCacheDir)
                            patchCacheDir.eachFileRecurse { File file->
                                String filePath = file.absolutePath

                                if(filePath.endsWith('.class')) {
                                    // 获取类名
                                    int beginIndex = filePath.lastIndexOf(patchCacheDir.name)+patchCacheDir.name.length()+1
                                    String className = filePath.substring(beginIndex, filePath.length()-6).replace('\\','.').replace('/','.')
                                    project.logger.error "className==============$className"
                                    // 获取混淆后类名
                                    String proguardName = map.get(className)
                                    patchList.add(proguardName)
                                }
                            }

                            println "list= $patchList"
                            // patchList保存的是需要打补丁的类名(混淆后)
                            // 1. 清除原类文件夹
                            FileUtils.cleanDirectory(patchCacheDir)

                            // 2. 将混淆的后jar包解压到当前目录
                            File proguardDir = new File("$project.buildDir\\intermediates\\transforms\\proguard")
                            proguardDir.eachFileRecurse {File file->
                                if(file.name.endsWith('.jar')) {
                                    File destDir = new File(file.parent,file.getName().replace('.jar',''))
                                    JarZipUtil.unzipJar(file.absolutePath,destDir.absolutePath)
                                    // 3. 遍历destDir, 将需要打补丁的类复制到cache目录
                                    destDir.eachFileRecurse {File f->
                                        String fPath = f.absolutePath
                                        if(fPath.endsWith('.class')) {
                                            // 获取类名
                                            int beginIndex = fPath.lastIndexOf(destDir.name) + destDir.name.length() + 1
                                            String className = fPath.substring(beginIndex, fPath.length() - 6).replace('\\', '.').replace('/', '.')

                                            project.logger.error "class=======================$className"
                                            // 是否是补丁，复制到cache目录
                                            if(patchList.contains(className)) {
                                                String destPath = className.replace(".","\\").concat('.class')
                                                File destFile = new File(patchCacheDir,destPath)
                                                FileUtils.copyFile(f, destFile)
                                            }
                                        }
                                    }
                                    FileUtils.deleteDirectory(destDir)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
