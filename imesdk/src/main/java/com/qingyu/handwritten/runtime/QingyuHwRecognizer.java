package com.qingyu.handwritten.runtime;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 单字手写分类器的离线 NCNN 运行时。
 *
 * 输入口径由 {@code preprocess.c} 决定（64×64 灰度、笔画≈1、背景≈0），输出是
 * {@code num_classes} 维 logits，由 native 侧做 softmax 并返回 Top-K。
 *
 * 字表顺序就是输出维度到字符的映射，错一位会静默输出错字，所以这里在加载时
 * 强校验类别数与字表哈希——宁可初始化失败让上层回退，也不要带着错表跑起来。
 */
public final class QingyuHwRecognizer implements AutoCloseable {
    private static boolean nativeLibraryLoaded;

    public static synchronized void loadNativeLibrary(String name) {
        if (!nativeLibraryLoaded) {
            System.loadLibrary(name);
            nativeLibraryLoaded = true;
        }
    }

    private long nativeHandle;
    private final String[] idxToChar;
    private final String modelVersion;

    public QingyuHwRecognizer(AssetManager assets, String paramName, String binName,
                              String charsetName) throws IOException {
        Charset charset = loadCharset(assets, charsetName);
        nativeHandle = nativeInit(assets, paramName, binName);
        if (nativeHandle == 0) throw new IOException("手写模型加载失败: " + paramName);
        idxToChar = charset.chars;
        modelVersion = charset.version;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public int classCount() {
        return idxToChar.length;
    }

    public Result[] predict(float[] input, int k) {
        if (nativeHandle == 0 || input.length != 64 * 64 || k <= 0) return new Result[0];
        int[] indices = new int[k];
        float[] probabilities = new float[k];
        int n = nativePredict(nativeHandle, input, indices, probabilities, k);
        Result[] result = new Result[Math.max(0, n)];
        for (int i = 0; i < result.length; i++) {
            int index = indices[i];
            String text = index >= 0 && index < idxToChar.length ? idxToChar[index] : "?";
            result[i] = new Result(text, probabilities[i]);
        }
        return result;
    }

    public boolean preprocess(byte[] gray, int width, int height, float[] output) {
        return nativeHandle != 0 && nativePreprocess(gray, width, height, output);
    }

    @Override public void close() {
        if (nativeHandle != 0) {
            nativeRelease(nativeHandle);
            nativeHandle = 0;
        }
    }

    private static final class Charset {
        final String[] chars;
        final String version;
        Charset(String[] chars, String version) {
            this.chars = chars;
            this.version = version;
        }
    }

    private static Charset loadCharset(AssetManager assets, String name) throws IOException {
        try (InputStream stream = assets.open(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                bytes.write(chunk, 0, count);
            }
            JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            JSONArray array = root.getJSONArray("classes");
            int declared = root.optInt("num_classes", array.length());
            if (declared != array.length()) {
                throw new IOException("字表损坏: num_classes=" + declared + " 但 classes 有 " + array.length() + " 项");
            }
            String[] chars = new String[array.length()];
            StringBuilder joined = new StringBuilder(array.length() * 2);
            for (int i = 0; i < array.length(); i++) {
                String text = array.getString(i);
                if (text.isEmpty()) throw new IOException("字表第 " + i + " 项为空");
                chars[i] = text;
                joined.append(text);
            }
            String expected = root.optString("classes_sha256", "");
            if (!expected.isEmpty()) {
                String actual = sha256(joined.toString());
                if (!actual.equalsIgnoreCase(expected)) {
                    throw new IOException("字表哈希不匹配: 期望 " + expected + " 实际 " + actual);
                }
            }
            return new Charset(chars, root.optString("model_version", root.optString("source", "")));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("手写字表加载失败: " + name, e);
        }
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    public static final class Result {
        public final String text;
        public final float probability;
        Result(String text, float probability) {
            this.text = text;
            this.probability = probability;
        }
    }

    private static native long nativeInit(AssetManager assets, String param, String bin);
    private static native int nativePredict(long handle, float[] input, int[] indices,
                                            float[] probabilities, int requested);
    private static native void nativeRelease(long handle);
    private static native boolean nativePreprocess(byte[] gray, int width, int height,
                                                   float[] output);
}
