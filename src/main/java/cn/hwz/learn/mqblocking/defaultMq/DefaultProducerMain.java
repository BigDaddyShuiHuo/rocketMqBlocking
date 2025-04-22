package cn.hwz.learn.mqblocking.defaultMq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

/**
 * @author needleHuo
 * @version jdk11
 * @description 同步消息生产者
 */
@Slf4j(topic = "c.DefaultProducerMain")
public class DefaultProducerMain {

    public static void main(String[] args) throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        DefaultMQProducer producer = new DefaultMQProducer("myProducer");
        producer.setNamesrvAddr("192.168.244.128:9876");
        producer.setSendMsgTimeout(15000);
        producer.start();
        log.debug("生产者启动完成.....");
        for (int i=0 ; i<1000 ; i++){
            Message msg
                    = new Message("dfTopic","dfTag",("a lot of message "+i).getBytes());
            producer.send(msg);
            // 睡个10毫秒，快速发送
            Thread.sleep(10);
        }
        log.debug("完成发送");
    }
}
