package com.aitsuki.plugin;

/**
 * Created by AItsuki on 2016/4/18.
 *
 */
public class Configure {

    //应用包名，依据这个来切割路径,获取一个类的完整类名
    public final static def appPackageName = 'com.aitsuki.hotpatchdemo'

    //AntilazyLoad.class所在的module名
    public final static def hackModuleName = 'hack'
    public final static def injectStr = 'System.out.println(com.aitsuki.hack.AntilazyLoad.class);'

    // 需要添加这两个jar包，否者javassist会报异常，获取到的compileSdkVersion一直为null，干脆写死了，自行更改
    public final static def androidJar = "D:\\Sdk\\platforms\\android-23\\android.jar"
    // 如果是android-22的话无需添加这个
    public final static def apacheJar = "D:\\Sdk\\platforms\\android-23\\optional\\org.apache.http.legacy.jar"

    // 排除不需要注入的类（只根据简单类名判断）
    public final static List<String> noInjectClasses = ['HotPatchApplication.class','BuildConfig.class','R.class']
    // 排除不需要注入的module
    public final static List<String> noInjectModules = ['hotpatch']
    // 如果类名包含以下关键字，不注入
    public final static List<String> noInjectKeyword = ['R$','android\\support\\','android.support.']

    public final static patchCacheDir = "D:\\patchCacheDir"
}
