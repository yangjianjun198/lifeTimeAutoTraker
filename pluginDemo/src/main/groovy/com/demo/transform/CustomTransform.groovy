package com.demo.transform

import com.android.build.api.transform.*
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.pipeline.TransformManager
import com.google.common.collect.Sets
import javassist.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

class CustomTransform extends Transform {
    private static final String IMPORT_CLASS_PATH = "com.yjj.learnDemox.MyClickManager"
    private static List<String> sClassHaveDealList = new ArrayList<>()
    private static ClassPool sClassPool = ClassPool.getDefault()
    private Project mProject
    private Map<String, MethodChecker> lifeMethodMap
    private MethodCodeInjector lifeMethodCodeInjector
    private MethodCodeInjector clickMethodCodeInjector
    private MethodChecker clickMethodChecker
    private boolean isLibrary

    CustomTransform(Project project, boolean isLibrary) {
        super()
        this.mProject = project
        this.isLibrary = isLibrary
        lifeMethodCodeInjector = new LifeMethodCodeInjectorImpl()
        clickMethodCodeInjector = new ClickCodeInjectorImpl()
        clickMethodChecker = new ViewClickMethodChecker()
        lifeMethodMap = new HashMap<>()
        lifeMethodMap.put("onCreate", new OnCreateMethodChecker())
        lifeMethodMap.put("onResume", new OnVoidMethodChecker("onResume"))
        lifeMethodMap.put("onPause", new OnVoidMethodChecker("onPause"))
        lifeMethodMap.put("onStart", new OnVoidMethodChecker("onStart"))
        lifeMethodMap.put("onStop", new OnVoidMethodChecker("onStop"))
        lifeMethodMap.put("onPause", new OnVoidMethodChecker("onPause"))
        lifeMethodMap.put("onDestroy", new OnVoidMethodChecker("onDestroy"))
    }

    @Override
    String getName() {
        return "LifeMethodTransformImpl"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<QualifiedContent.Scope> getScopes() {
        if (isLibrary) {
            return Sets.immutableEnumSet(QualifiedContent.Scope.PROJECT)
        } else {
            return TransformManager.SCOPE_FULL_PROJECT
        }
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(Context context, Collection<TransformInput> inputs,
        Collection<TransformInput> referencedInputs, TransformOutputProvider outputProvider,
        boolean isIncremental) throws IOException, TransformException, InterruptedException {
        String packageName
        if (isLibrary) {
            packageName = mProject.properties.get("packageName")
            if (packageName == null) {
                throw new IOException("library must set packageName in gradle.properties")
            }
        } else {
            packageName = mProject.extensions.findByType(AppExtension).defaultConfig.applicationId
        }
        packageName = packageName.replaceAll('\\.', '/')
        inputs.each { TransformInput input ->
            input.directoryInputs.each { DirectoryInput directoryInput ->
                injectCode(directoryInput.file.getAbsolutePath(), packageName, mProject)

                // 获取output目录
                def dest = outputProvider.getContentLocation(directoryInput.name,
                    directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
                FileUtils.copyDirectory(directoryInput.file, dest)
            }

            input.jarInputs.each { JarInput jarInput ->
                injectCode(jarInput.file.getAbsolutePath(), packageName, mProject)
                def jarName = jarInput.name
                def md5Name = DigestUtils.md5Hex(jarInput.file.getPath())
                if (jarName.endsWith(".jar")) {
                    jarName = jarName.substring(0, jarName.length() - 4)
                }
                def dest = outputProvider.getContentLocation(jarName + md5Name,
                    jarInput.contentTypes, jarInput.scopes, Format.JAR)
                FileUtils.copyFile(jarInput.file, dest)
            }
        }
    }

    private void injectCode(String path, String packageName, Project project) {
        sClassPool.appendClassPath(path)
        sClassPool.appendClassPath(project.android.bootClasspath[0].toString())
        sClassPool.importPackage(IMPORT_CLASS_PATH)
        File dir = new File(path)
        if (dir.isDirectory()) {
            dir.eachFileRecurse { File file ->
                String filePath = file.absolutePath
                if (checkIsValidFile(filePath)) {
                    int index = filePath.indexOf(packageName)
                    boolean isMyPackage = index != -1
                    if (!isMyPackage) {
                        return
                    }
                    String className = getClassName(index, filePath)
                    if (className == null || className.isEmpty()) {
                        return
                    }
                    CtClass ctClass = sClassPool.getCtClass(className)
                    if (!checkCtClassIsActivityOrFragment(ctClass)) {
                        return
                    }
                    if (ctClass.isFrozen()) {
                        ctClass.defrost()
                    }
                    if (sClassHaveDealList.contains(className)) {
                        ctClass.writeFile(path)
                        ctClass.detach()
                        return
                    }
                    boolean success = false
                    for (CtMethod method : ctClass.getMethods()) {
                        if (checkIsLifeMethod(method)) {
                            if (ctClass != method.getDeclaringClass()) {
                                method = overrideMethod(ctClass, method)
                            }
                            try {
                                lifeMethodCodeInjector.inject(method)
                                success = true
                            } catch (Exception e) {
                                System.out.println("inject error but can ignore" + e)
                            }
                        }
                    }
                    if (success) {
                        sClassHaveDealList.add(className)
                    }
                    ctClass.writeFile(path)
                    ctClass.detach()
                }
            }
        }
    }

    private boolean checkCtClassIsActivityOrFragment(CtClass ctClass) {
        if (ctClass == null) {
            return false
        }
        CtClass activity = sClassPool.getCtClass("android.app.Activity")
        if (ctClass.subclassOf(activity)) {
            return true
        }
        return false
    }

    private CtMethod overrideMethod(CtClass ctClass, CtMethod getConnectionMethodOfSuperclass)
        throws NotFoundException, CannotCompileException {
        final CtMethod m = CtNewMethod.delegator(getConnectionMethodOfSuperclass, ctClass);
        ctClass.addMethod(m)
        return m
    }

    private boolean checkIsLifeMethod(CtMethod method) {
        String methodName = method.name
        MethodChecker checker = lifeMethodMap.get(methodName)
        if (checker == null) {
            return false
        }
        return checker.check(method)
    }

    private boolean checkIsValidFile(String filePath) {
        filePath.endsWith(".class") && !filePath.contains('R$') &&
            !filePath.contains('R.class') &&
            !filePath.contains("R2.class") &&
            !filePath.contains("BuildConfig.class")
    }

    private String getClassName(int index, String fileName) {
        if (!fileName.endsWith(".class")) {
            return ""
        }
        fileName = fileName.substring(index)

        fileName = fileName.substring(0, fileName.length() - ".class".length())
        return fileName.replaceAll("/", '.')
    }

    private void injectMethod(CtMethod method) {
        clickMethodCodeInjector.inject(method)
    }

    private boolean checkOnClickMethod(CtMethod method) {
        return clickMethodChecker.check(method)
    }
}