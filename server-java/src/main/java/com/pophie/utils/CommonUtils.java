package com.pophie.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class CommonUtils {

    private CommonUtils() {}

    public static synchronized String getUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String getRandNum(int min, int max) {
        int randNum = min + new Random().nextInt(max - min + 1);
        return String.valueOf(randNum);
    }

    public static String getIpAddr(HttpServletRequest request) {
        if (request == null) return "";
        String ip = request.getHeader("x-forwarded-for");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("Proxy-Client-IP");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) ip = request.getHeader("WL-Proxy-Client-IP");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
            if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
                try {
                    InetAddress inet = InetAddress.getLocalHost();
                    ip = inet.getHostAddress();
                } catch (UnknownHostException ignored) {}
            }
        }
        if (StringUtils.hasText(ip) && ip.length() > 15 && ip.indexOf(',') > 0) {
            ip = ip.substring(0, ip.indexOf(','));
        }
        return ip == null ? "" : ip;
    }

    public static String timeFormat(Date time) {
        return new SimpleDateFormat("yyyy-MM-dd").format(time);
    }

    public static String timeFormat2(Date time) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(time);
    }

    public static int formatInteger(Integer integer) {
        return integer == null ? 0 : integer;
    }

    public static BigDecimal getNum(Double num) {
        return num == null ? BigDecimal.ZERO : BigDecimal.valueOf(num);
    }

    public static BigDecimal getNum(Long num) {
        return num == null ? BigDecimal.ZERO : BigDecimal.valueOf(num);
    }

    public static BigDecimal getNum(Integer num) {
        return num == null ? BigDecimal.ZERO : BigDecimal.valueOf(num);
    }

    public static String builderUrl(String requestUrl, String path, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder().append(requestUrl).append(path);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '?') sb.append('?');
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('&');
            }
        }
        String url = sb.toString();
        if (url.endsWith("&") || url.endsWith("?")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static Map<String, Object> objectToMap(Object obj) throws Exception {
        if (obj == null) return null;
        Map<String, Object> map = new HashMap<>();
        BeanInfo beanInfo = Introspector.getBeanInfo(obj.getClass());
        for (PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
            String key = pd.getName();
            if ("class".equalsIgnoreCase(key)) continue;
            Method getter = pd.getReadMethod();
            map.put(key, getter == null ? null : getter.invoke(obj));
        }
        return map;
    }

    public static Object mapToObject(Map<String, Object> map, Class<?> beanClass) throws Exception {
        if (map == null) return null;
        Object obj = beanClass.getDeclaredConstructor().newInstance();
        BeanInfo beanInfo = Introspector.getBeanInfo(obj.getClass());
        for (PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
            Method setter = pd.getWriteMethod();
            if (setter != null && map.containsKey(pd.getName())) setter.invoke(obj, map.get(pd.getName()));
        }
        return obj;
    }
}
