package com.pophie.schema;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 感知输入。对应 schemas.py PerceptionInput。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PerceptionInput {

    @JsonDeserialize(using = FacialExpressionDeserializer.class)
    private FacialExpression facialExpression;

    private VoiceProsody voice;

    private String touch;

    @JsonDeserialize(using = IdentityDeserializer.class)
    private String identity;

    private GestureAction gesture;

    private PostureAction posture;
}
