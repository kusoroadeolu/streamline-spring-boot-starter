package com.github.kusoroadeolu.streamline.utils;

public class ApiUtils {

    private ApiUtils(){}

    public static void assertNotNull(Object o, String message){
        if (isNull(o)) throw new IllegalArgumentException(message);
    }

    private static boolean isNull(Object o){
        return o == null;
    }


}
