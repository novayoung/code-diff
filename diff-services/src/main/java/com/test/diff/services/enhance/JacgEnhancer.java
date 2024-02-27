package com.test.diff.services.enhance;

import javassist.ClassClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringBootConfiguration;

@Slf4j
public class JacgEnhancer {

    private static boolean enhanced = false;

    public synchronized static void enhance() {
        if (enhanced) {
            return;
        }
        enhanced = true;
        try {
            ClassPool classPool = ClassPool.getDefault();
            ClassClassPath classPath = new ClassClassPath(SpringBootConfiguration.class);
            classPool.insertClassPath(classPath);
            CtClass ctClass = classPool.get("com.adrninistrator.jacg.util.JACGCallGraphFileUtil");
            ctClass.defrost();
            CtMethod ctMethod = ctClass.getDeclaredMethod("getEmptyCallGraphFileName");
            ctMethod.insertBefore("$2=com.test.diff.services.enhance.JacgEnhancer.replaceEmptyMethod($2);");
            ctClass.toClass();
        } catch (Throwable e) {
            log.warn(e.getMessage(), e);
        }
    }

    public static String replaceEmptyMethod(String method) {
        int idx = method.lastIndexOf("(");
        if (idx == -1) {
            return method;
        }
        return method.substring(0, idx);
    }
}
