package com.test.diff.services.utils;

import cn.hutool.core.io.FileUtil;
import lombok.SneakyThrows;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * 解压jar的工具类
 * @author
 * @version 1.0.0
 */
public class JarUtil {

    protected static Log log = LogFactory.getLog(JarUtil.class);

    public static void uncompress(File jarFile, File tarDir) {
        Set<String> prefixes = new HashSet<>();
        prefixes.add("BOOT-INF");
        uncompress(jarFile, tarDir, prefixes);
    }

    @SneakyThrows
    @SuppressWarnings("resource")
    public static void uncompress(File jarFile, File tarDir, Set<String> prefixes) {
        JarFile jfInst = new JarFile(jarFile);
        Enumeration enumEntry = jfInst.entries();
        while (enumEntry.hasMoreElements()) {
            JarEntry jarEntry = (JarEntry) enumEntry.nextElement();
            String entryName = jarEntry.getName();
            if (prefixes.stream().noneMatch(entryName::startsWith)) {
                continue;
            }
            File tarFile = new File(tarDir, jarEntry.getName());
            if(entryName.contains("META-INF")){
                File miFile = new File(tarDir, "META-INF");
                if(!miFile.exists()){
                    miFile.mkdirs();
                }
            }
            makeFile(jarEntry, tarFile);
            if (jarEntry.isDirectory()) {
                continue;
            }
            FileChannel fileChannel = new FileOutputStream(tarFile).getChannel();
            InputStream ins = jfInst.getInputStream(jarEntry);
            transferStream(ins, fileChannel);
        }
        jfInst.close();
    }

    /**
     * 流交换操作
     * @param ins 输入流
     * @param channel 输出流
     */
    private static void transferStream(InputStream ins, FileChannel channel) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024 * 10);
        ReadableByteChannel rbcInst = Channels.newChannel(ins);
        try {
            while (-1 != (rbcInst.read(byteBuffer))) {
                byteBuffer.flip();
                channel.write(byteBuffer);
                byteBuffer.clear();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (null != rbcInst) {
                try {
                    rbcInst.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (null != channel) {
                try {
                    channel.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 打印jar文件内容信息
     * @param file jar文件
     */
    private static void printJarEntry(File file) {
        JarFile jfInst = null;;
        try {
            jfInst = new JarFile(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
        Enumeration enumEntry = jfInst.entries();
        while (enumEntry.hasMoreElements()) {
            log.info((enumEntry.nextElement()));
        }
    }

    /**
     * 创建文件
     * @param jarEntry jar实体
     * @param fileInst 文件实体
     * @throws IOException 抛出异常
     */
    private static void makeFile(JarEntry jarEntry, File fileInst) {
        if (!fileInst.exists()) {
            if (jarEntry.isDirectory()) {
                fileInst.mkdirs();
            } else {
                try {
                    FileUtil.mkdir(fileInst.getParentFile());
                    fileInst.createNewFile();
                } catch (IOException e) {
                    log.error("创建文件失败>>>".concat(fileInst.getPath()), e);
                }
            }
        }
    }

    public static boolean hasAny(File jarFile, String... prefixes) {
        Set<String> prefixSet = Arrays.stream(prefixes).collect(Collectors.toSet());
        try (JarFile jfInst = new JarFile(jarFile) ) {
            Enumeration enumEntry = jfInst.entries();
            while (enumEntry.hasMoreElements()) {
                JarEntry jarEntry = (JarEntry) enumEntry.nextElement();
                String entryName = jarEntry.getName();
                if (prefixSet.stream().anyMatch(entryName::startsWith)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
