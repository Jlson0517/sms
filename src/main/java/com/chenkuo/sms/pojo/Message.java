package com.chenkuo.sms.pojo;

import lombok.Data;

import java.io.Serializable;

/**
 * @author WYJ
 * @ClassName: Message
 * @Description: TODO
 * @date 2018/10/1113:15
 */
@Data
public class Message implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;

    private String telephone;

    private String message;

    private String sign;

    @Override
    public String toString() {
        return "messageId=" + messageId + "&telephone=" + telephone + "&message=" + message;
    }
}
