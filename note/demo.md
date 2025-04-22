### 参考demo

#### 生产者代码

```java
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
            // 睡个10毫秒，模拟快速发送
            Thread.sleep(10);
        }
        log.debug("完成发送");
    }
}
```



#### 造成阻塞的消费者代码

```java
@Slf4j(topic = "c.DefaultConsumerMain")
public class DefaultConsumerMain {
    public static void main(String[] args) throws MQClientException {
        // 拉取模式
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("myConsumer");
        consumer.setNamesrvAddr("192.168.244.128:9876");
        consumer.subscribe("dfTopic", "*");

        // 每次就拉一条
        consumer.setConsumeMessageBatchMaxSize(1);
        // 一个线程去消费
        consumer.setConsumeThreadMin(1);
        // 没有救急线程
        consumer.setConsumeThreadMax(1);

        // 调整分配策略，默认是平均分配，把执行权分配出去，就是另外一个消费者执行完了，由于没有执行权
        // 也不会消费队列里的剩余消息
        consumer.setAllocateMessageQueueStrategy(new AllocateMessageQueueAveragelyByCircle());

        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> list, ConsumeConcurrentlyContext consumeConcurrentlyContext) {

                String message = new String(list.get(0).getBody());

                log.debug("当前线程:{}，接受到的消息:{}",Thread.currentThread().getName(),message);
                // 故意消费得慢些
                ThreadSleep.sleep(5);
                // 默认返回成功
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
        log.debug("消费者启动完成......");
    }
}
```





#### 临时消费者代码

```java
@Slf4j(topic = "c.DefaultConsumerMain")
public class TempConsumerMain {
    public static void main(String[] args) throws MQClientException, NoSuchFieldException, InterruptedException, InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        // 拉取模式
        DefaultMQPushConsumer consumer = createTempConsumer();

        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> list, ConsumeConcurrentlyContext consumeConcurrentlyContext) {

                String message = new String(list.get(0).getBody());
                /**
                 * 模拟消费。可以先保存到数据库或者转发到另外一个topic，先迅速降低队列中的，消息数，防止堵住
                 * 这里直接用打印来模拟
                 */
                log.debug("当前线程:{}，接受到的消息:{}",Thread.currentThread().getName(),message);

                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });

        consumer.start();
        log.debug("消费者启动完成......");
        // 低于500条就关闭
        monitorShutDown(consumer,500);

    }

    /**
     * 创建临时消费者
     * @return
     * @throws MQClientException
     */
    public static DefaultMQPushConsumer createTempConsumer() throws MQClientException {
        // 拉取模式
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("myConsumer");
        consumer.setNamesrvAddr("192.168.244.128:9876");
        consumer.subscribe("dfTopic", "*");

        // 多拉几条
        consumer.setConsumeMessageBatchMaxSize(32);
        // 扩大4倍，加快消费
        consumer.setConsumeThreadMin(4);
        // 救急线程设置多一些
        consumer.setConsumeThreadMax(8);
        // 调整分配策略
        consumer.setAllocateMessageQueueStrategy(new AllocateMessageQueueAveragelyByCircle());
        return consumer;
    }

    public static void monitorShutDown(DefaultMQPushConsumer consumer,long threshold){
        long start =System.currentTimeMillis();
        // 单线程监控是否终止
        ScheduledExecutorService executore = Executors.newSingleThreadScheduledExecutor();
        executore.scheduleWithFixedDelay(()->{
            try {
                long surplus = getSurplus(consumer, "dfTopic");
                log.debug("剩余数量：{}",surplus);
                // 达到预期关闭值了
                if (surplus<threshold){
                    // 关闭超时时间先简单设置100秒
                    shutdown(consumer,100000);
                    log.debug("消费者启动关闭......{}",System.currentTimeMillis()-start);
                    executore.shutdown();
                }
            } catch (Exception e) {
                log.debug("出现异常{}",e.getMessage());
                throw new RuntimeException(e);
            }
        },1,1, TimeUnit.SECONDS);

    }

    /**
     * 停止消费者
     * @param consumer
     */
    public static void shutdown(DefaultMQPushConsumer consumer,long timeOut) throws NoSuchFieldException, InvocationTargetException, IllegalAccessException, NoSuchMethodException, InterruptedException {
        // 停止接收
        consumer.suspend();
        long start = System.currentTimeMillis();
        // 收尾工作，如果有还在进行中的消息，等他完成
        while(getProcessingMsCount(consumer)>0){
            // 需要等待的时间
            if (System.currentTimeMillis()-start>=timeOut){
                // 2. 获取所有消息队列
                Set<MessageQueue> allQueues = consumer.getDefaultMQPushConsumerImpl()
                        .getRebalanceImpl()
                        .getProcessQueueTable()
                        .keySet();
                // 持久化当前消费位置
                consumer.getDefaultMQPushConsumerImpl().getOffsetStore().persistAll(allQueues);
                break;
            }
            Thread.sleep(500);
        }
        consumer.shutdown();
    }

    /**
     * 获取正在处理中的消息数量
     * @param consumer
     * @return
     */
    public static int getProcessingMsCount(DefaultMQPushConsumer consumer) throws NoSuchFieldException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        // 获取rebalanceImpl
        DefaultMQPushConsumerImpl impl = consumer.getDefaultMQPushConsumerImpl();;
        RebalanceImpl rebalanceImpl = impl.getRebalanceImpl();

        // 获取队列映射表
        ConcurrentMap<MessageQueue, ProcessQueue> processQueueTable = rebalanceImpl.getProcessQueueTable();



        // 统计数量
        int total = 0;
        for (ProcessQueue queue: processQueueTable.values()){
            total = total+queue.getMsgCount().intValue();
        }
        return total;
    }

    /**
     * 获取Broker中剩下的消息
     * @param consumer
     * @return
     */
    public static long getSurplus(DefaultMQPushConsumer consumer,String topic) throws MQClientException {
        Set<MessageQueue> messageQueues = consumer.fetchSubscribeMessageQueues(topic);
        long totalOffset=0;
        for (MessageQueue queue:messageQueues){
            // 获取最新消息偏移量（最后接收的消息偏移量）
            long maxOffset = consumer.maxOffset(queue);

            // 从本地缓存获取消费位点
            long consumerOffset = consumer.getDefaultMQPushConsumerImpl()
                    .getOffsetStore()
                    .readOffset(queue, ReadOffsetType.MEMORY_FIRST_THEN_STORE);

            // 获取队列已消费的消息偏移量
            totalOffset = totalOffset+(maxOffset-consumerOffset);
        }
        return totalOffset;
    }
}
```





#### 涉及到的成员变量

RebalanceImpl：给消费者分配队列

processQueueTable：本地队列表

OffsetStore：存储消费到什么位置的，提交到broker的时候会给broker提交一个分布式锁，以此保证数据一致性，本地则通过双重校验锁保证数据一致性

![image-20250422153719498](C:\Users\59755\AppData\Roaming\Typora\typora-user-images\image-20250422153719498.png)