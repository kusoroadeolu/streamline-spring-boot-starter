package com.github.kusoroadeolu.streamline.utils;

public class ApiUtils {

    private ApiUtils(){}

    public static void assertNotNull(Object o, String message){
        if (isNull(o)) throw new IllegalArgumentException(message);
    }

    public static void assertPositive(Number val, String message){
        if (val.longValue() < 0) throw new IllegalArgumentException(message);
    }


    public static void assertBetween(Number val, int i, int l ,String message){
        final var longVal = val.longValue();
        if (longVal < i || longVal > l) throw new IllegalArgumentException(message);
    }

    public static void assertTrue(boolean val, String message){
        if (!val) throw new IllegalArgumentException(message);
    }

    private static boolean isNull(Object o){
        return o == null;
    }


}
