package com.aitsuki.plugin

import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import org.apache.commons.io.FileUtils

/**
 * Created by AItsuki on 2016/4/7.
 * 注入代码分为两种情况，一种是目录，需要遍历里面的class进行注入
 * 另外一种是jar包，需要先解压jar包，注入代码之后重新打包成jar
 */
public class Inject {

    private static ClassPool pool= ClassPool.getDefault()

    /**
     * 添加classPath到ClassPool
     * @param libPath
     */
    public static void appendClassPath(String libPath) {
        pool.appendClassPath(libPath)
    }

    /**
     * 遍历该目录下的所有class，对所有class进行代码注入。
     * 其中以下class是不需要注入代码的：
     * --- 1. R文件相关
     * --- 2. 配置文件相关（BuildConfig）
     * --- 3. Application
     * @param path 目录的路径
     */
    public static void injectDir(String path) {
        pool.appendClassPath(path)
        File dir = new File(path)
        if (dir.isDirectory()) {
            dir.eachFileRecurse { File file ->

                String filePath = file.absolutePath
                String packageName = Configure.appPackageName.replace(".","\\")
                if (filePath.endsWith(".class") && needInject(filePath)) {

                    int index = filePath.indexOf(packageName)
                    if (index != -1) {
                        int end = filePath.length() - 6 // .class = 6
                        String className = filePath.substring(index, end).replace('\\', '.').replace('/', '.')
                        injectClass(className, path)
                    }
                }
            }
        }
    }

    /**
     * 这里需要将jar包先解压，注入代码后再重新生成jar包
     * @path jar包的绝对路径
     */
    public static void injectJar(String path) {
        if (path.endsWith(".jar")) {
            File jarFile = new File(path)

            // jar包解压后的保存路径
            String jarZipDir = jarFile.getParent() + "/" + jarFile.getName().replace('.jar', '')

            // 解压jar包, 返回jar包中所有class的完整类名的集合（带.class后缀）
            List classNameList = JarZipUtil.unzipJar(path, jarZipDir)

            // 删除原来的jar包
            jarFile.delete()

            // 注入代码
            pool.appendClassPath(jarZipDir)
            for (String className : classNameList) {
                if (className.endsWith(".class") && needInject(className)) {
                    className = className.substring(0, className.length() - 6)
                    println "===========preInject$className============================"
                    injectClass(className, jarZipDir)
                }
            }

            println "===========preZipJar============================"

            // 从新打包jar
            JarZipUtil.zipJar(jarZipDir, path)

            // 删除目录
            FileUtils.deleteDirectory(new File(jarZipDir))
        }
    }

    private static void injectClass(String className, String path) {


        CtClass c = pool.getCtClass(className)
        if (c.isFrozen()) {
            c.defrost()
        }

        CtConstructor[] cts = c.getDeclaredConstructors()
        println "===========cts=$cts============================"
        if (cts == null || cts.length == 0) {
            insertNewConstructor(c)
        } else {
            cts[0].insertBeforeBody(Configure.injectStr)
        }
        c.writeFile(path)
        c.detach()
    }

    private static void insertNewConstructor(CtClass c) {
        CtConstructor constructor = new CtConstructor(new CtClass[0], c)
        constructor.setBody("{\n$Configure.injectStr\n}")
        c.addConstructor(constructor)
    }

    /**
     * 判断某个类是否需要注入代码
     * @param className 该类的绝对路径  如：d:\aitsuki\project\demo.class
     * @return
     */
    public static boolean needInject(String className) {
        def flag = true
        for(String noInjectClass :Configure.noInjectClasses) {
            if(className.endsWith(noInjectClass)) {
                flag = false
                break
            }
        }

        if(flag) {
            for(String noInjectClass :Configure.noInjectKeyword) {
                if(className.contains(noInjectClass)) {
                    flag = false
                    break
                }
            }
        }
        return flag
    }

}
