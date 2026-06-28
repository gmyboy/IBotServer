package com.pophie.schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 主人档案。对应 schemas.py OwnerProfile，含字段归一化与校验：
 * - nickname/robot_name：strip
 * - gender：lower，必须 ∈ {male,female,other}，否则报错
 * - birthday：必须匹配 YYYY-MM-DD，否则报错
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OwnerProfile {

    private static final Set<String> VALID_GENDERS = Set.of("male", "female", "other");
    private static final Pattern BIRTHDAY_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private String nickname;
    private String robotName;
    private String gender;
    private String birthday;
    private boolean faceRegistered = false;

    /** 归一化 + 校验，校验失败抛 IllegalArgumentException（消息与 Python 一致）。 */
    public static OwnerProfile normalized(String nickname, String robotName, String gender,
                                          String birthday, boolean faceRegistered) {
        String nk = nickname == null ? null : nickname.trim();
        String rn = robotName == null ? null : robotName.trim();

        String g;
        if (gender == null || gender.isEmpty()) {
            g = null;
        } else {
            g = gender.trim().toLowerCase();
            if (!VALID_GENDERS.contains(g)) {
                throw new IllegalArgumentException("gender must be male, female, or other");
            }
        }

        String b;
        if (birthday == null || birthday.isEmpty()) {
            b = null;
        } else {
            b = birthday.trim();
            if (!BIRTHDAY_RE.matcher(b).matches()) {
                throw new IllegalArgumentException("birthday must be YYYY-MM-DD");
            }
        }

        return new OwnerProfile(nk, rn, g, b, faceRegistered);
    }
}
