package com.whislte;

import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 
 *
 */
public class App {

    static Logger log = Logger.getLogger(App.class);

    public static void main(String[] args) {

        String domain_prop = "";
        String driver_prop = "";
        String url_prop = "";
        String user_prop = "";
        String password_prop = "";
        String time_prop = "";
        boolean flag = false;
        FileInputStream in = null;
        try {
            Properties properties = new Properties();
            in = new FileInputStream("eleTag.properties");
            properties.load(in);
            domain_prop = properties.getProperty("domain");
            driver_prop = properties.getProperty("driver");
            url_prop = properties.getProperty("url");
            user_prop = properties.getProperty("user");
            password_prop = properties.getProperty("password");
            time_prop = properties.getProperty("time");
            flag = true;
        } catch (Exception e) {
            e.printStackTrace();
            log.error("读取配置信息失败！", e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        log.info("domain_prop : " + domain_prop + ", driver_prop : " + driver_prop + ", url_prop : " + url_prop
                + ", user_prop : " + user_prop + ", password_prop : " + password_prop + "time_prop : " + time_prop
                + ", flag : " + flag);
        if (!flag) {
            return;
        }
        String domain = domain_prop;
        String driver = driver_prop;
        String url = url_prop;
        String user = user_prop;
        String password = password_prop;
        long time = Integer.parseInt(time_prop);

        ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(1);
        scheduled.scheduleWithFixedDelay(() -> {
            Connection connection = null;
            String uri = "";
            try {
                Class.forName(driver);
                connection = DriverManager.getConnection(url, user, password);
                connection.setAutoCommit(false);
                // 1.检查回退业务
                Statement docStatement = connection.createStatement();
                String sql = "select distinct taskid from kd_pick_job";
                log.info("docStatement.executeQuery_sql : " + sql);
                ResultSet docResultSet = docStatement.executeQuery(sql);
                log.info("docResultSet : " + docResultSet);
                StringBuilder docBuilder = new StringBuilder();
                if (docResultSet != null) {
                    while (docResultSet.next()) {
                        Integer docId = docResultSet.getInt("taskid");
                        docBuilder.append(docId).append(",");
                    }
                    docResultSet.close();
                }
                if (docStatement != null) {
                    docStatement.close();
                }
                // 创建默认的httpclient
                CloseableHttpClient httpClient = HttpClients.createDefault();
                String docIds = docBuilder.toString();
                log.info("delPackingApi_docIds : " + docIds);
                if (docIds != null && !"".equals(docIds)) {
                    docIds = docIds.substring(0, docIds.length() - 1);
                    // 调用ibs删除拣货单API
                    uri = domain + "/api/eletag/delPacking";
                    // 创建post请求对象
                    HttpPost httpPost = new HttpPost(uri);
                    // 装填请求参数
                    List<NameValuePair> list = new ArrayList<NameValuePair>();
                    list.add(new BasicNameValuePair("docIds", docIds));
                    // 设置参数到请求对象中
                    httpPost.setEntity(new UrlEncodedFormEntity(list, "utf-8"));
                    // 执行请求操作，并拿到结果（同步阻塞）
                    log.info("execute_delPackingApi");
                    CloseableHttpResponse response = httpClient.execute(httpPost);
                    log.info("delPackingApi.response : " + response);
                    // 获取结果实体
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        String responseList = EntityUtils.toString(entity, "utf-8");
                        log.info("delPackingApi.resonseList : " + responseList);
                        ObjectMapper objectMapper = new ObjectMapper();
                        ApiResponse delPackingResponse = objectMapper.readValue(responseList, ApiResponse.class);
                        String message = delPackingResponse.getMessage();
                        if (!"".equals(message) && !"success".equals(message)) {
                            log.error("delPackingResponse.error : " + message);
                            Statement errorStatement = connection.createStatement();
                            sql = "update kd_pick_job set apimemo = '" + message + "' where taskid in (" + docIds + ")";
                            log.info("errorStatement.executeUpdate.sql : " + sql);
                            errorStatement.executeUpdate(sql);
                            if (errorStatement != null) {
                                errorStatement.close();
                            }
                        }
                        List<Map<String, String>> result = (List<Map<String, String>>) delPackingResponse.getResult();
                        log.info("delPackingResponse.result : " + result);
                        // 记录已回退拣货单id
                        StringBuilder errorBuilder = new StringBuilder();
                        if (result != null && result.size() > 0) {
                            for (Map<String, String> map : result) {
                                map.forEach((key, value) -> {
                                    if ("error".equals(value)) {
                                        errorBuilder.append(key).append(",");
                                    }
                                });
                            }
                        }

                        String errorIds = errorBuilder.toString();
                        log.info("delPackingResponse.errorIds : " + errorIds);
                        if (errorIds != null && !"".equals(errorIds)) {
                            errorIds = errorIds.substring(0, errorIds.length() - 1);
                            // 将已回退的拣货单同步至任务历史记录表,并更新处理状态为回退(2),删除任务调度表表中已回退记录
                            sql = "insert into kd_pick_lst select job.*, sysdate createDate, 2 usestatus from kd_pick_job job where job.taskid in ("
                                    + errorIds + ")";
                            log.info("delPackingStatement.sql : " + sql);
                            Statement delPackingStatement = connection.createStatement();
                            delPackingStatement.execute(sql);
                            sql = "delete from kd_pick_job where taskid in (" + errorIds + ")";
                            log.info("delPackingStatement.sql : " + sql);
                            delPackingStatement.execute(sql);
                            if (delPackingStatement != null) {
                                delPackingStatement.close();
                            }
                        }
                    }

                    // 关流
                    EntityUtils.consume(entity);
                    response.close();
                }

                // 2.更新拣货完成
                sql = "select row_id from kd_pick_job where status = 1";
                Statement dtlStatement = connection.createStatement();
                log.info("dtlStatement.executeQuery_sql : " + sql);
                ResultSet dtlResultSet = dtlStatement.executeQuery(sql);
                log.info("dtlResultSet : " + dtlResultSet);
                StringBuilder dtlBuilder = new StringBuilder();
                if (dtlResultSet != null) {
                    while (dtlResultSet.next()) {
                        Integer dtlId = dtlResultSet.getInt("row_id");
                        dtlBuilder.append(dtlId).append(",");
                    }
                    dtlResultSet.close();
                }
                if (dtlStatement != null) {
                    dtlStatement.close();
                }
                String dtlIds = dtlBuilder.toString();
                log.info("updatePackingApi_dtlIds : " + dtlIds);
                if (dtlIds != null && !"".equals(dtlIds)) {
                    // 调用ibs更新拣货单API
                    dtlIds = dtlIds.substring(0, dtlIds.length() - 1);
                    // 调用ibs更新拣货单API
                    uri = domain + "/api/eletag/updatePacking";
                    // 创建post请求对象
                    HttpPost httpPost = new HttpPost(uri);
                    // 装填请求参数
                    List<NameValuePair> list = new ArrayList<NameValuePair>();
                    list.add(new BasicNameValuePair("dtlIds", dtlIds));
                    // 设置参数到请求对象中
                    httpPost.setEntity(new UrlEncodedFormEntity(list, "utf-8"));
                    // 执行请求操作，并拿到结果（同步阻塞）
                    log.info("execute_updatePackingApi");
                    CloseableHttpResponse response = httpClient.execute(httpPost);
                    log.info("updatePackingApi.response : " + response);
                    // 获取结果实体
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        String responseList = EntityUtils.toString(entity, "utf-8");
                        log.info("updatePackingApi.resonseList : " + responseList);
                        ObjectMapper objectMapper = new ObjectMapper();
                        ApiResponse updatePackingResponse = objectMapper.readValue(responseList, ApiResponse.class);
                        String message = updatePackingResponse.getMessage();
                        if (!"".equals(message) && !"success".equals(message)) {
                            log.error("updatePackingResponse.error : " + message);
                            sql = "update kd_pick_job set apimemo = '" + message + "' where row_id in (" + dtlIds + ")";
                            Statement errorStatement = connection.createStatement();
                            log.info("updatePackingApi.sql : " + sql);
                            errorStatement.executeUpdate(sql);
                            if (errorStatement != null) {
                                errorStatement.close();
                            }
                        }
                        List<Map<String, String>> result = (List<Map<String, String>>) updatePackingResponse
                                .getResult();
                        log.info("updatePackingResponse.result : " + result);
                        // 记录返回成功/失败信息
                        StringBuilder successBuilder = new StringBuilder();
                        if (result != null && result.size() > 0) {
                            for (Map<String, String> map : result) {
                                for (Entry<String, String> entry : map.entrySet()) {
                                    String key = entry.getKey();
                                    String value = entry.getValue();
                                    if ("ok".equals(value)) {
                                        successBuilder.append(key).append(",");
                                    } else {
                                        // 将返回error的数据，更新error信息到kd_pick_job.apimemo
                                        String errorSql = "update kd_pick_job set apimemo = '" + value
                                                + "' where row_id = " + key;
                                        Statement updateErrorStatement = connection.createStatement();
                                        log.info("updatePackingResponse.errorSql : " + errorSql);
                                        updateErrorStatement.executeUpdate(errorSql);
                                        if (updateErrorStatement != null) {
                                            updateErrorStatement.close();
                                        }
                                    }
                                }
                            }
                        }

                        String successIds = successBuilder.toString();
                        log.info("updatePackingResponse.successIds : " + successIds);
                        if (successIds != null && !"".equals(successIds)) {
                            successIds = successIds.substring(0, successIds.length() - 1);
                            // 将返回ok的数据转移到kd_pick_lst,转移时kd_pick_lst.usestatus=1,删除任务调度表表中记录
                            sql = "insert into kd_pick_lst select job.*, sysdate createDate, 1 usestatus from kd_pick_job job where job.row_id in ("
                                    + successIds + ")";
                            Statement updatePackingStatement = connection.createStatement();
                            log.info("updatePackingStatement.sql : " + sql);
                            updatePackingStatement.execute(sql);
                            sql = "delete from kd_pick_job where row_id in (" + successIds + ")";
                            log.info("updatePackingStatement.sql : " + sql);
                            updatePackingStatement.execute(sql);
                            if (updatePackingStatement != null) {
                                updatePackingStatement.close();
                            }
                        }
                    }

                    // 关流
                    EntityUtils.consume(entity);
                    response.close();
                }

                // 3.增加待拣货 调用ibs读取拣货单
                uri = domain + "/api/eletag/selectPacking";
                // 创建post请求对象
                HttpPost httpPost = new HttpPost(uri);
                // 执行请求操作，并拿到结果（同步阻塞）
                log.info("execute_selectPackingApi");
                CloseableHttpResponse response = httpClient.execute(httpPost);
                log.info("selectPackingApi.response : " + response);
                // 获取结果实体
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    String responseList = EntityUtils.toString(entity, "utf-8");
                    log.info("selectPackingApi.resonseList : " + responseList);
                    ObjectMapper objectMapper = new ObjectMapper();
                    ApiResponse selectPackingResponse = objectMapper.readValue(responseList, ApiResponse.class);
                    String message = selectPackingResponse.getMessage();
                    if (!"".equals(message) && !"success".equals(message)) {
                        log.error("selectPackingApi.error : " + message);
                    }
                    Map<String, List<Map<String, Object>>> result = (Map<String, List<Map<String, Object>>>) selectPackingResponse
                            .getResult();
                    log.info("selectPackingApi.result : " + result);

                    if (result != null && result.size() > 0) {
                        for (Entry<String, List<Map<String, Object>>> packingEntry : result.entrySet()) {
                            String storeid = packingEntry.getKey();
                            List<Map<String, Object>> packingList = packingEntry.getValue();
                            sql = "select taskid from kd_pick_job where storeid = " + storeid;
                            Statement checkPackingStatement = connection.createStatement();
                            log.info("selectPackingApi.sql : " + sql);
                            ResultSet queryResult = checkPackingStatement.executeQuery(sql);
                            // 检查该库区ID是否在中间表kd_pick_job存在；若已经存在，则跳过,不存在则插入到中间表
                            if (queryResult != null && queryResult.next()) {
                                log.info("selectPackingApi.checkResult.continue");
                                queryResult.close();
                                checkPackingStatement.close();
                                continue;
                            }

                            if (packingList != null && packingList.size() > 0) {
                                for (Map<String, Object> packing : packingList) {
                                    if (packing != null) {
                                        Statement insertPackingStatement = connection.createStatement();
                                        String jobdate = (String) packing.get("jobdate");
                                        Integer taskid = (Integer) packing.get("taskid");
                                        Integer row_id = (Integer) packing.get("row_id");
                                        Integer goodsid = (Integer) packing.get("goodsid");
                                        String locator = (String) packing.get("locator");
                                        String lot = (String) packing.get("lot");
                                        String qty = (String) packing.get("qty");
                                        Integer companyid = (Integer) packing.get("companyid");
                                        String companyname = (String) packing.get("companyname");
                                        String inputman = (String) packing.get("inputman") != null
                                                ? (String) packing.get("inputman") : "";
                                        String packingno = (String) packing.get("packingno");
                                        String deliveryno = (String) packing.get("deliveryno");
                                        String goodsname = (String) packing.get("goodsname");
                                        String storename = (String) packing.get("storename");

                                        sql = "insert into kd_pick_job (jobdate, taskid, jobtype, inouttype, row_id, goodsid, locator, lot,"
                                                + " qty, companyid, companyname, status, inputman, storeid, packingno, deliveryno, goodsname, storename)"
                                                + " values (TO_DATE('" + jobdate + "','yyyy-mm-dd hh24:mi:ss'), "
                                                + taskid + "," + "1, '拣货单', " + row_id + ", " + goodsid + ", '"
                                                + locator + "', '" + lot + "', " + qty + ", " + companyid + ", '"
                                                + companyname + "', 0, '" + inputman + "', " + storeid + ", '"
                                                + packingno + "', '" + deliveryno + "', '" + goodsname + "', '"
                                                + storename + "')";
                                        log.info("selectPackingApi.insert_sql : " + sql);
                                        insertPackingStatement.execute(sql);
                                        if (insertPackingStatement != null) {
                                            insertPackingStatement.close();
                                        }
                                    }
                                }
                            }
                            if (queryResult != null) {
                                queryResult.close();
                            }
                            if (checkPackingStatement != null) {
                                checkPackingStatement.close();
                            }

                        }
                    }

                }

                // 关流
                EntityUtils.consume(entity);
                response.close();
                httpClient.close();
                connection.commit();
                log.info("scheduled_commit........................................");
            } catch (Exception ex) {
                ex.printStackTrace();
                log.error("scheduled error", ex);
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        log.error("connection close error", ex);
                    }
                }
            }
        } , 0, time, TimeUnit.SECONDS);

    }
}
