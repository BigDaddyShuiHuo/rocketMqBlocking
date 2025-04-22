### dockers部署mq踩坑



#### 1.docker拉取报错

拉取报错：Error response from daemon: Get "https://registry-1.docker.io/v2/": net/http: request canceled while waiting for connection (Client.Timeout exceeded while awaiting headers)



原因：国内阿里云，腾讯云，华为云镜像库都失效了，我设置的是这三个镜像源

解决办法：寻找可用镜像源，修改/etc/docker/daemon.json文件

```java
{
  "max-concurrent-downloads": 3,
  "max-download-attempts": 5,
  "shutdown-timeout": 30,
  "storage-driver": "overlay2",
  "debug": true,
  "registry-mirrors" : 
    [ 
      "https://docker.m.daocloud.io",
      "https://docker.xuanyuan.me", 
      "https://docker.1ms.run"
    ],
  "dns": ["8.8.8.8", "114.114.114.114"]
}
```





#### 2.使用rocketMq默认的启动无法启动

原因：默认配置有问题

解决：自行编写docker-compose.yml和broker的配置文件,在docker-compose.yml添加容器卷映射



docker-compose.yml

```
version: '3'

services:
  namesrv:
    image: apache/rocketmq:latest
    container_name: rocketmq-nameserver
    ports:
      - 9876:9876
    volumes:
      - ./data/namesrv/logs:/root/logs
      - ./data/namesrv/store:/root/store
    command: sh mqnamesrv

  broker:
    image: apache/rocketmq:latest
    container_name: rocketmq-broker
    links:
      - namesrv
    ports:
      - 10909:10909
      - 10911:10911
    volumes:
      - ./data/broker/logs:/root/logs
      - ./data/broker/store:/root/store
      - ./custom_broker.conf:/home/rocketmq/rocketmq-5.3.2/conf/broker.conf
    environment:
      - NAMESRV_ADDR=namesrv:9876
      - JAVA_OPT_EXT=-server -Xms1g -Xmx1g -Xmn512m
      - BROKER_IP=192.168.244.128
    command: sh mqbroker -c /home/rocketmq/rocketmq-5.3.2/conf/broker.conf

  dashboard:
    image: apacherocketmq/rocketmq-dashboard:latest
    container_name: rocketmq-dashboard
    ports:
      - 8080:8080
    environment:
      - JAVA_OPTS=-Drocketmq.namesrv.addr=namesrv:9876
    depends_on:
      - namesrv
```



broker配置文件custom_broker.conf



```java
brokerClusterName = DefaultCluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
brokerIP1 = 192.168.244.128
listenPort = 10911
namesrvAddr = namesrv:9876
autoCreateTopicEnable=true
```



#### 启动生产者报错

生产者报错connect to 172.18.0.4:10911 failed

原因：容器内broker的地址是172.18.0.4:10911，namesrv返回生产者，生产者拿到这个ip发现无法连接，这里应该要返回服务器的实际ip

解决办法：通过docker-compose.yml和broker的配置文件写死服务器ip地址解决

docker-compose.yml配置

```
# docker-compose.yml
services:
  broker:
    network_mode: host  # 直接使用主机网络
    # 或者使用端口映射
    ports:
      - "10911:10911"
    environment:
      - BROKER_IP=92.168.244.128
```



broker配置文件

```
brokerIP1 = 192.168.244.128  # 必须设置为可路由的IP
namesrvAddr = nameserver:9876
```