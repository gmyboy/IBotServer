package com.pophie.schema;

import lombok.Data;

/** 机器人输出。对应 schemas.py RobotOutput，含 finalize()。 */
@Data
public class RobotOutput {
    private String text = "";
    private FacialExpression facialExpression = FacialExpression.neutral;
    private String facialExpressionLabel;
    private RobotState robotState;
    private String robotStateLabel;
    private VoiceProsody voice;
    private AudioPayload audio;
    private GestureAction gesture;
    private PostureAction posture;

    /**
     * 补全 label，robot_state 缺省时按表情推导，保证回复始终携带合法 FSM 状态。
     * 对应 schemas.py RobotOutput.finalize()。
     */
    public RobotOutput finalizeOutput() {
        this.facialExpressionLabel = Schemas.facialExpressionLabel(this.facialExpression);
        if (this.robotState == null) {
            this.robotState = Schemas.robotStateForExpression(this.facialExpression);
        }
        this.robotStateLabel = Schemas.ROBOT_STATE_LABELS.get(this.robotState);
        return this;
    }
}
