package com.antiaddiction.data;

/**
 * 实名认证结果封装类
 */
public class VerificationResult {

    private final boolean valid;
    private final int age;
    private final boolean minor;
    private final String message;

    private VerificationResult(boolean valid, int age, boolean minor, String message) {
        this.valid = valid;
        this.age = age;
        this.minor = minor;
        this.message = message;
    }

    public static VerificationResult invalid(String reason) {
        return new VerificationResult(false, -1, false, reason);
    }

    public static VerificationResult invalid() {
        return invalid("姓名或身份证号不正确");
    }

    public static VerificationResult valid(int age, boolean minor) {
        String msg = minor ? "认证成功（未成年人，将开启防沉迷限制）" : "认证成功（成年人）";
        return new VerificationResult(true, age, minor, msg);
    }

    public boolean isValid()   { return valid; }
    public int getAge()        { return age; }
    public boolean isMinor()   { return minor; }
    public String getMessage() { return message; }
}
