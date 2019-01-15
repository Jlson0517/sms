package com.chenkuo.sms.controller;

import com.alibaba.fastjson.JSONObject;
import com.chenkuo.sms.pojo.Message;
import com.chenkuo.sms.yunpian.YunUtils;
import com.yunpian.sdk.model.Result;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.text.ParseException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * @author WYJ
 * @ClassName: MessageController
 * @Description: TODO
 * @date 2018/10/1113:23
 */
@Controller
@RequestMapping("/message")
public class MessageController {

    private static final Logger logger = LoggerFactory.getLogger(MessageController.class);

    @Autowired
    private YunUtils yunUtils;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //流水号时间戳校验超时时间
    private static final int OUT_TIME = 300000;

    @Value("${auth.secret_key}")
    private String secretKey;

    @RequestMapping("/send")
    @ResponseBody
    public void send(@RequestBody Message message) {
        int check = tradeAuth(message);
        if (0 == check) {
            Result result = yunUtils.singleSend(message.getTelephone(), message.getMessage());
            if (result.isSucc()) {
                stringRedisTemplate.opsForValue().set("message:id:" + message.getMessageId(), message.getMessageId(), OUT_TIME, TimeUnit.MILLISECONDS);
            }
            logger.info("{}:{}-{}, result is {}", message.getMessageId(), message.getTelephone(), message.getMessage(), result.getMsg());
        } else {
            String error;
            switch (check) {
                case 1:
                    error = "over time";
                    break;
                case 2:
                    error = "message duplication";
                    break;
                case 3:
                    error = "sign error";
                    break;
                default:
                    error = "unknown error";
                    break;
            }
            logger.error("{}:{}-{} error, caused by {}", message.getMessageId(), message.getTelephone(), message.getMessage(), error);
        }
    }

    private int tradeAuth(Message message) {
        int result = 0;
        try {
            //消息ID时间戳校验
            Date createDate = DateUtils.parseDate(StringUtils.substring(message.getMessageId(), 0, 17), "yyyyMMddHHmmssSSS");
            long createTime = createDate.getTime();
            long currentTime = System.currentTimeMillis();
            long time = Math.abs(currentTime - createTime);
            if (time > OUT_TIME) {
                result = 1;
            }

            //消息ID幂等性校验
            if (stringRedisTemplate.hasKey("message:id:" + message.getMessageId())) {
                return 2;
            }

            //签名校验
            String sign = DigestUtils.md5Hex(message.toString() + "&secret=" + secretKey);
            if (!sign.equals(message.getSign())) {
                return 3;
            }
        } catch (ParseException e) {
            logger.error("{}: exception caused by {}", message.getMessageId(), e.getCause().getMessage());
        }
        return result;
    }
}
