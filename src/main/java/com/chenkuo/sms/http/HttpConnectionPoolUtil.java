package com.chenkuo.sms.http;

import com.alibaba.fastjson.JSONObject;
import com.chenkuo.sms.pojo.Message;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.http.*;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author WYJ
 * @ClassName: HttpConnectionPoolUtil
 * @Description: TODO
 * @date 2018/9/2917:42
 */
public class HttpConnectionPoolUtil {

    private static Logger logger = LoggerFactory.getLogger(HttpConnectionPoolUtil.class);

    //重试次数
    private static final int RETRY = 3;

    //与服务器连接超时时间
    private static final int CONNECTION_TIMEOUT = 3000;

    //从连接池中获取连接的超时时间
    private static final int CONN_REQUEST_TIMEOUT = 1000;

    //从服务器获取响应数据的超时时间
    private static final int SOCKET_TIMEOUT = 5000;

    //最大连接数
    private static final int MAX_CONN = 200;

    //每个路由最大连接数
    private static final int MAX_PRE_ROUTE = 50;

    private static final int MAX_ROUTE = 50;

    //发送请求的客户端单例
    private static CloseableHttpClient httpClient;

    //连接池管理类
    private static PoolingHttpClientConnectionManager manager;

    //
    private static ScheduledExecutorService monitorExecutor;

    // 相当于线程锁,用于线程安全
    private final static Object SYNC_LOCK = new Object();

    /**
     * @Description: 对http请求进行基本设置
     * @param: httpRequestBase http请求
     * @return: void
     * @author: WYJ
     * @date: 2018/9/30 9:49
     */
    private static void setRequestConfig(HttpRequestBase httpRequestBase) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(CONN_REQUEST_TIMEOUT)
                .setConnectTimeout(CONNECTION_TIMEOUT)
                .setSocketTimeout(SOCKET_TIMEOUT)
                .build();
        httpRequestBase.setConfig(requestConfig);
    }

    /**
     * @Description: 设置参数
     * @param: httpPost
     * @param: param
     * @return: void
     * @author: WYJ
     * @date: 2018/9/30 11:03
     */
    private static void setPostParams(HttpPost httpPost, StringEntity entity) {
        entity.setContentEncoding("UTF-8");
        httpPost.setEntity(entity);
    }

    private static CloseableHttpClient getHttpClient(String url) {
        String hostName = StringUtils.split(url, "//")[1];
        int port = 80;
        if (hostName.contains(":")) {
            String[] args = StringUtils.split(hostName, ":");
            hostName = args[0];
            port = Integer.parseInt(args[1]);
        }

        //多线程下多个线程同时调用getHttpClient容易导致重复创建httpClient对象的问题,所以加上了同步锁
        if (httpClient == null) {
            synchronized (SYNC_LOCK) {
                if (httpClient == null) {
                    //开启监控线程,对异常和空闲线程进行关闭
                    httpClient = createHttpClient(hostName, port);
                    monitorExecutor = new ScheduledThreadPoolExecutor(1, new BasicThreadFactory.Builder().namingPattern("scheduled-pool-%d").daemon(true).build());
                    monitorExecutor.scheduleAtFixedRate(new TimerTask() {
                        @Override
                        public void run() {
                            //关闭异常连接
                            manager.closeExpiredConnections();
                            //关闭600s空闲的连接
                            manager.closeIdleConnections(600, TimeUnit.SECONDS);
                            logger.info("close expired and idle for over 600s connection");
                        }
                    }, 5, 600, TimeUnit.SECONDS);
                }
            }
        }
        return httpClient;
    }

    /**
     * 根据host和port构建httpclient实例
     *
     * @param host 要访问的域名
     * @param port 要访问的端口
     * @return
     */
    private static CloseableHttpClient createHttpClient(String host, int port) {
        ConnectionSocketFactory plainSocketFactory = PlainConnectionSocketFactory.getSocketFactory();
        LayeredConnectionSocketFactory sslSocketFactory = SSLConnectionSocketFactory.getSocketFactory();
        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create().register("http", plainSocketFactory).register("https", sslSocketFactory).build();
        manager = new PoolingHttpClientConnectionManager(registry);
        //设置最大连接数
        manager.setMaxTotal(MAX_CONN);
        //每个路由默认最大连接数
        manager.setDefaultMaxPerRoute(MAX_PRE_ROUTE);

        //请求失败时,进行请求重试
        HttpRequestRetryHandler handler = (e, i, httpContext) -> {
            //重试超过3次,放弃请求
            if (i > RETRY) {
                logger.error("retry has more than 3 time, give up request");
                return false;
            }

            //服务器没有响应,可能是服务器断开了连接,应该重试
            if (e instanceof NoHttpResponseException) {
                logger.error("receive no response from server, retry");
                return true;
            }

            //SSL握手异常不需要重试
            if (e instanceof SSLHandshakeException) {
                logger.error("SSL hand shake exception");
                return false;
            }

            //超时
            if (e instanceof InterruptedIOException) {
                logger.error("InterruptedIOException");
                return false;
            }

            //服务器不可达
            if (e instanceof UnknownHostException) {
                logger.error("server host unknown");
                return false;
            }

            //连接超时
            if (e instanceof ConnectTimeoutException) {
                logger.error("Connection Time out");
                return false;
            }

            //SSL握手异常
            if (e instanceof SSLException) {
                logger.error("SSLException");
                return false;
            }

            HttpClientContext context = HttpClientContext.adapt(httpContext);
            HttpRequest request = context.getRequest();
            //请求幂等再次尝试
            return !(request instanceof HttpEntityEnclosingRequest);
        };

        return HttpClients.custom().setConnectionManager(manager).setRetryHandler(handler).build();
    }

    public static String doPost(String url, Map<String, Object> params) {
        String requestParams = JSONObject.toJSONString(params);
        return doPost(url, requestParams);
    }

    public static String doPost(String url, String params) {
        StringEntity entity = new StringEntity(params, "UTF-8");
        entity.setContentType("application/json");
        return doPost(url, entity);
    }

    public static String doPost(String url, List<NameValuePair> params) {
        try {
            StringEntity entity = new UrlEncodedFormEntity(params, "UTF-8");
            return doPost(url, entity);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String doPost(String url, StringEntity entity) {
        HttpPost httpPost = new HttpPost(url);
        setRequestConfig(httpPost);
        setPostParams(httpPost, entity);
        CloseableHttpResponse httpResponse = null;
        InputStream in = null;
        try {
            httpResponse = getHttpClient(url).execute(httpPost, HttpClientContext.create());
            if (HttpStatus.SC_OK == httpResponse.getStatusLine().getStatusCode()) {
                HttpEntity httpEntity = httpResponse.getEntity();
                if (httpEntity != null) {
                    in = httpEntity.getContent();
                    return IOUtils.toString(in, "utf-8");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (httpResponse != null) {
                    httpResponse.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public static String doGet(String url) {
        HttpGet httpGet = new HttpGet(url);
        setRequestConfig(httpGet);
        CloseableHttpResponse response = null;
        InputStream in = null;
        String result = null;
        try {
            response = getHttpClient(url).execute(httpGet, HttpClientContext.create());
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    in = entity.getContent();
                    result = IOUtils.toString(in, "utf-8");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return result;
    }

    public static JSONObject post(String url, String params) {
        CloseableHttpClient httpclient = HttpClientBuilder.create().build();
        HttpPost post = new HttpPost(url);
        JSONObject response = null;
        try {
            StringEntity s = new StringEntity(params);
            s.setContentEncoding("UTF-8");
            //发送json数据需要设置contentType
            s.setContentType("application/json");
            post.setEntity(s);
            HttpResponse res = httpclient.execute(post);
            if (res.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                // 返回json格式：
                String result = EntityUtils.toString(res.getEntity());
                response = JSONObject.parseObject(result);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return response;
    }

    /**
     * 关闭连接池
     */
    public static void closeConnectionPool() {
        try {
            httpClient.close();
            manager.close();
            monitorExecutor.shutdown();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
