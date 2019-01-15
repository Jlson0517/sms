package com.chenkuo.sms.yunpian;

import com.yunpian.sdk.YunpianClient;
import com.yunpian.sdk.model.Result;
import com.yunpian.sdk.model.SmsSingleSend;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * @author WYJ
 * @ClassName: YunUtils
 * @Description: TODO
 * @date 2018/10/1111:13
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunpian.api")
public class YunUtils {
    private static final Logger logger = LoggerFactory.getLogger(YunUtils.class);

    private String key;

    private YunpianClient yunpianClient = null;

    @PostConstruct
    public void init() {
        yunpianClient = new YunpianClient(key);
        yunpianClient.init();
    }

    /**
     * @Description: TODO
     * @param: mobile 手机号
     * @param: text 短信内容
     * @return: com.yunpian.sdk.model.Result<com.yunpian.sdk.model.SmsSingleSend>
     * @author: WYJ
     * @date: 2018/10/11 13:15
     */
    public Result<SmsSingleSend> singleSend(String mobile, String text) {
        Map<String, String> params = new HashMap<>();
        params.put("apikey", key);
        params.put("mobile", mobile);
        params.put("text", text);
        Result<SmsSingleSend> result = yunpianClient.sms().single_send(params);
        return result;
    }
}
