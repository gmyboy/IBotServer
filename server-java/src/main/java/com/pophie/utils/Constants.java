package com.pophie.utils;

public class Constants {

    public static final String TOKEN = "token";

    public static final String USER_ID = "userId";

    public static final int MAX_PAGE_COUNT = 100;

    public static class CommonAnnotation {
        public static final String ANNO_OPERATION = "operation";
    }

    public static class Competence {
        public static final int ONLY_READ = 0;
        public static final int READ_AND_WRITE = 1;
        public static final int ALL = 2;
    }

    public static class Operator {
        public static final String ADD = "add";
        public static final String DELETE = "delete";
        public static final String UPDATE = "update";
        public static final String FIND = "find";
        public static final String OTHER = "other";
    }
}
