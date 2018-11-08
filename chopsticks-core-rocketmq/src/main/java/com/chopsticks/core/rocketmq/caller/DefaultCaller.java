package com.chopsticks.core.rocketmq.caller;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.store.ReadOffsetType;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.chopsticks.core.Const;
import com.chopsticks.core.caller.Caller;
import com.chopsticks.core.caller.InvokeCommand;
import com.chopsticks.core.caller.InvokeResult;
import com.chopsticks.core.caller.NoticeCommand;
import com.chopsticks.core.caller.NoticeResult;
import com.chopsticks.core.concurrent.Promise;
import com.chopsticks.core.concurrent.impl.GuavaPromise;
import com.chopsticks.core.concurrent.impl.GuavaTimeoutPromise;
import com.chopsticks.core.exception.InvokeException;
import com.chopsticks.core.rocketmq.caller.impl.BatchInvokerSender;
import com.chopsticks.core.rocketmq.caller.impl.DefaultInvokeCommand;
import com.chopsticks.core.rocketmq.caller.impl.DefaultNoticeCommand;
import com.chopsticks.core.rocketmq.caller.impl.SingleInvokeSender;
import com.chopsticks.core.rocketmq.handler.InvokeResponse;
import com.google.common.base.Optional;
import com.google.common.base.Throwables; 

public class DefaultCaller implements Caller {
	
	private static final Logger log = LoggerFactory.getLogger(DefaultCaller.class);
	
	private String namesrvAddr;
	
	private String groupName;
	
	private DefaultMQProducer producer;
	
	private DefaultMQPushConsumer callerInvokeConsumer;
	
	private volatile boolean started;
	
	protected static final long DEFAULT_TIMEOUT_MILLIS = 1000 * 30L;
	
	private static final MessageQueueSelector DEFAULT_MESSAGE_QUEUE_SELECTOR = new OrderedMessageQueueSelector();
	
	private long batchExecuteIntervalMillis = TimeUnit.MILLISECONDS.toMillis(-1L);
	
	private InvokeSender invokeSender;
	
	private boolean invokable = true;
	
	/**
	 *  <msgid, timeoutGuavaPromise>
	 */
	private Map<String, GuavaPromise<BaseInvokeResult>> callerInvokePromiseMap;
	
	public DefaultCaller(String groupName) {
		checkArgument(!isNullOrEmpty(groupName), "groupName cannot be null or empty");
		this.groupName = groupName;
	}
	
	private void testCaller() {
		try {
			String topic = buildRespTopic();
			InvokeResponse resp = new InvokeResponse("testCaller", com.chopsticks.core.rocketmq.Const.CLIENT_TIME.getNow(), com.chopsticks.core.rocketmq.Const.CLIENT_TIME.getNow(), new byte[0]);
			SendResult ret = producer.send(new Message(topic, com.chopsticks.core.rocketmq.Const.INVOCE_RESP_TAG_SUFFIX, JSON.toJSONBytes(resp)));
			if(ret.getSendStatus() != SendStatus.SEND_OK) {
				throw new RuntimeException(ret.getSendStatus().name());
			}
		}catch (Throwable e) {
			if(e instanceof MQClientException) {
				String errMsg = e.getMessage();
				if(errMsg.contains(com.chopsticks.core.rocketmq.Const.ERROR_MSG_NO_ROUTE_INFO_OF_THIS_TOPIC)){
					e = new InvokeException("namesrv connection error", e);
				}else if(errMsg.contains(com.chopsticks.core.rocketmq.Const.ERROR_MSG_NO_NAME_SERVER_ADDRESS)) {
					e = new InvokeException("namesrv ip undefined", e);
				}
			}
			Throwables.throwIfUnchecked(e);
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public synchronized void start() {
		if(!started) {
			callerInvokePromiseMap = new ConcurrentHashMap<String, GuavaPromise<BaseInvokeResult>>();
			producer = buildAndStartProducer();
			invokeSender = buildInvokeSender(producer, batchExecuteIntervalMillis);
			callerInvokeConsumer = buildAndStartCallerInvokeConsumer();
			started = true;
		}
	}
	


	private InvokeSender buildInvokeSender(DefaultMQProducer producer, long batchExecuteIntervalMillis) {
		InvokeSender invokeSender = null;
		if(isInvokable()) {
			if(batchExecuteIntervalMillis > 0L) {
				invokeSender = new BatchInvokerSender(producer, batchExecuteIntervalMillis);
			}else {
				invokeSender = new SingleInvokeSender(producer);
			}
		}
		return invokeSender;
	}

	@Override
	public synchronized void shutdown() {
		if(producer != null) {
			producer.shutdown();
			if(invokeSender != null) {
				invokeSender.shutdown();
			}
		}
		if(callerInvokeConsumer != null) {
			callerInvokeConsumer.shutdown();
		}
		started = false;
	}

	private DefaultMQPushConsumer buildAndStartCallerInvokeConsumer() {
		DefaultMQPushConsumer callerInvokeConsumer = null;
		if(isInvokable()) {
			callerInvokeConsumer = new DefaultMQPushConsumer(com.chopsticks.core.rocketmq.Const.CONSUMER_PREFIX + getGroupName() + com.chopsticks.core.rocketmq.Const.CALLER_INVOKE_CONSUMER_SUFFIX);
			callerInvokeConsumer.setNamesrvAddr(namesrvAddr);
			callerInvokeConsumer.setConsumeThreadMin(Const.AVAILABLE_PROCESSORS);
			callerInvokeConsumer.setConsumeThreadMax(Const.AVAILABLE_PROCESSORS);
			callerInvokeConsumer.setMessageModel(MessageModel.BROADCASTING);
			callerInvokeConsumer.setConsumeMessageBatchMaxSize(10);
			callerInvokeConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
			callerInvokeConsumer.registerMessageListener(new CallerInvokeListener(callerInvokePromiseMap));
			callerInvokeConsumer.setPullThresholdSizeForTopic(50);
			try {
				String topic = buildRespTopic();
				callerInvokeConsumer.subscribe(topic, com.chopsticks.core.rocketmq.Const.ALL_TAGS);
				callerInvokeConsumer.start();
				callerInvokeConsumer = com.chopsticks.core.rocketmq.Const.buildConsumer(callerInvokeConsumer);
				testCaller();
				callerInvokeConsumer.fetchSubscribeMessageQueues(buildRespTopic());	
			}catch (Throwable e) {
				if(callerInvokeConsumer != null) {
					callerInvokeConsumer.shutdown();
				}
				Throwables.throwIfUnchecked(e);
				throw new RuntimeException(e);
			}
		}
		return callerInvokeConsumer;
	}

	private DefaultMQProducer buildAndStartProducer() {
		DefaultMQProducer producer = null;
		producer = new DefaultMQProducer(com.chopsticks.core.rocketmq.Const.PRODUCER_PREFIX + getGroupName());
		producer.setNamesrvAddr(namesrvAddr);
		producer.setRetryAnotherBrokerWhenNotStoreOK(true);
		producer.setDefaultTopicQueueNums(com.chopsticks.core.rocketmq.Const.DEFAULT_TOPIC_QUEUE_SIZE);
		try {
			producer.start();
			return producer;
		}catch (Throwable e) {
			Throwables.throwIfUnchecked(e);
			throw new RuntimeException(e);
		}
	}
	
	public BaseInvokeResult invoke(BaseInvokeCommand cmd) {
		return this.invoke(cmd, DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
	}

	public BaseInvokeResult invoke(BaseInvokeCommand cmd, long timeout, TimeUnit timeoutUnit) {
		try {
			return this.asyncInvoke(cmd, timeout, timeoutUnit).get();
		} catch (Throwable e) {
			if(e instanceof ExecutionException) {
				e = e.getCause();
			}
			if(e instanceof CancellationException) {
				e = new TimeoutException();
			}
			Throwables.throwIfUnchecked(e);
			throw new RuntimeException(e);
		}
	}

	public Promise<BaseInvokeResult> asyncInvoke(BaseInvokeCommand cmd) {
		return this.asyncInvoke(cmd, DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
	}

	public Promise<BaseInvokeResult> asyncInvoke(final BaseInvokeCommand cmd, final long timeout, final TimeUnit timeoutUnit) {
		checkArgument(started, "must be call method start");
		checkArgument(invokable, "must be support invokable");
		final GuavaTimeoutPromise<BaseInvokeResult> promise = new GuavaTimeoutPromise<BaseInvokeResult>(timeout, timeoutUnit);
		try {
			InvokeRequest req = buildInvokeRequest(cmd, timeout, timeoutUnit);
			callerInvokePromiseMap.put(req.getReqId(), promise);
			promise.addListener(new CallerTimoutPromiseListener(callerInvokePromiseMap, req.getReqId()));
			Message msg = buildInvokeMessage(req, cmd, timeout, timeoutUnit);
			invokeSender.send(msg, promise);
		} catch (Throwable e) {
			promise.setException(e);
		}
	
		return promise;
	}
	
	
	public BaseNoticeResult notice(BaseNoticeCommand cmd) {
		return this.notice(cmd, (String)null);
	}

	public BaseNoticeResult notice(BaseNoticeCommand cmd, Object orderKey) {
		try {
			return this.asyncNotice(cmd, orderKey).get();
		}catch (Throwable e) {
			if(e instanceof ExecutionException) {
				e = e.getCause();
			}
			Throwables.throwIfUnchecked(e);
			throw new RuntimeException(e);
		}
	}
	
	public Promise<BaseNoticeResult> asyncNotice(BaseNoticeCommand cmd) {
		return this.asyncNotice(cmd, null);
	}
	
	public Promise<BaseNoticeResult> asyncNotice(final BaseNoticeCommand cmd, final Object orderKey) {
		checkArgument(started, "must be call method start");
		final GuavaPromise<BaseNoticeResult> promise = new GuavaPromise<BaseNoticeResult>();
		try {
			Message msg = buildNoticeMessage(cmd, orderKey);
			NoticeSendCallback callback = new NoticeSendCallback(promise);
			if(orderKey == null) {
				producer.send(msg, callback);
			}else {
				producer.send(msg, DEFAULT_MESSAGE_QUEUE_SELECTOR , orderKey, callback);
			}
		}catch (Throwable e) {
			promise.setException(e);
		}
	
		return promise;
	}
	
	protected Message buildInvokeMessage(InvokeRequest req, BaseInvokeCommand cmd, long timeout, TimeUnit timeoutUnit) {
		Message msg = new Message(buildTopic(cmd), cmd.getTag(), cmd.getBody());
		msg.putUserProperty(com.chopsticks.core.rocketmq.Const.INVOKE_REQUEST_KEY, JSON.toJSONString(req));
		return msg;
	}
	
	private DelayNoticeRequest buildDelayNoticeRequest(BaseNoticeCommand cmd, long timeout, TimeUnit timeoutUnit) {
		DelayNoticeRequest req = new DelayNoticeRequest();
		req.setExecuteTime(com.chopsticks.core.rocketmq.Const.CLIENT_TIME.getNow() + timeoutUnit.toMillis(timeout));
		return req;
	}

	protected InvokeRequest buildInvokeRequest(BaseInvokeCommand cmd, long timeout, TimeUnit timeoutUnit) {
		InvokeRequest req = new InvokeRequest();
		req.setReqId(UUID.randomUUID().toString());
		req.setReqTime(com.chopsticks.core.rocketmq.Const.CLIENT_TIME.getNow());
		req.setDeadline(req.getReqTime() + timeoutUnit.toMillis(timeout));
		req.setRespTopic(buildRespTopic());
		req.setRespTag(cmd.getTag() + com.chopsticks.core.rocketmq.Const.INVOCE_RESP_TAG_SUFFIX);
		return req;
	}

	private String buildRespTopic() {
		return getGroupName() + com.chopsticks.core.rocketmq.Const.INVOCE_RESP_TOPIC_SUFFIX;
	}
	
	private Message buildDelayNoticeMessage(BaseNoticeCommand cmd, Long delay, TimeUnit delayTimeUnit) {
		Message msg = buildNoticeMessage(cmd, null);
		if(delay != null 
		&& delayTimeUnit != null
		&& delay > 0) {
			Optional<Entry<Long, Integer>> delayLevel = com.chopsticks.core.rocketmq.Const.getDelayLevel(delayTimeUnit.toMillis(delay));
			if(delayLevel.isPresent()) {
				if(!delay.equals(delayLevel.get().getKey())) {
					DelayNoticeRequest req = buildDelayNoticeRequest(cmd, delay, delayTimeUnit);
					msg.putUserProperty(com.chopsticks.core.rocketmq.Const.DELAY_NOTICE_REQUEST_KEY, JSON.toJSONString(req));
				}
				msg.setDelayTimeLevel(delayLevel.get().getValue());
			}else {
				log.warn("unsupport notice delay");
			}
		}
		return msg;
	}
	
	private Message buildNoticeMessage(BaseNoticeCommand cmd, Object orderKey) {
		Message msg = new Message(buildTopic(cmd, orderKey), cmd.getTag(), cmd.getBody());
		return msg;
	}
	
	private String buildTopic(BaseCommand cmd) {
		return buildTopic(cmd, null);
	}
	
	private String buildTopic(BaseCommand cmd, Object orderKey) {
		String topic = cmd.getTopic();
		if(cmd instanceof BaseInvokeCommand) {
			topic = buildInvokeTopic(topic);
		}else if(cmd instanceof BaseNoticeCommand) {
			if(orderKey == null) {
				topic = buildNoticeTopic(topic);
			}else {
				topic = buildOrderedNoticeTopic(topic);
			}
		}
		return buildSuccessTopic(topic);
	}
	
	protected String buildSuccessTopic(String topic) {
		return topic.replaceAll("\\.", "_").replaceAll("\\$", "-");
	}
	
	protected String buildOrderedNoticeTopic(String topic) {
		return topic + com.chopsticks.core.rocketmq.Const.ORDERED_NOTICE_TOPIC_SUFFIX;
	}
	
	protected String buildNoticeTopic(String topic) {
		return topic + com.chopsticks.core.rocketmq.Const.NOTICE_TOPIC_SUFFIX;
	}
	
	protected String buildInvokeTopic(String topic) {
		return topic + com.chopsticks.core.rocketmq.Const.INVOKE_TOPIC_SUFFIX;
	}

	protected DefaultMQProducer getProducer() {
		return producer;
	}
	
	protected String getNamesrvAddr() {
		return namesrvAddr;
	}
	public void setNamesrvAddr(String namesrvAddr) {
		this.namesrvAddr = namesrvAddr;
	}
	protected String getGroupName() {
		return groupName;
	}

	@Override
	public InvokeResult invoke(InvokeCommand cmd) {
		return this.invoke(cmd, DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
	}

	@Override
	public InvokeResult invoke(InvokeCommand cmd, long timeout, TimeUnit timeoutUnit) {
		try {
			return this.asyncInvoke(cmd, timeout, timeoutUnit).get();
		} catch (Throwable e) {
			if(e instanceof ExecutionException) {
				e = e.getCause();
			}
			if(e instanceof CancellationException) {
				e = new TimeoutException();
			}
			Throwables.throwIfUnchecked(e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public Promise<? extends InvokeResult> asyncInvoke(InvokeCommand cmd) {
		return this.asyncInvoke(cmd, DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
	}

	@Override
	public Promise<? extends InvokeResult> asyncInvoke(InvokeCommand cmd, long timeout, TimeUnit timeoutUnit) {
		return this.asyncInvoke(buildBaseInvokeCommand(cmd), timeout, timeoutUnit);
	}

	@Override
	public NoticeResult notice(NoticeCommand cmd) {
		return this.notice(cmd, null);
	}

	@Override
	public NoticeResult notice(NoticeCommand cmd, Object orderKey) {
		try {
			return this.asyncNotice(cmd, orderKey).get();
		}catch (Throwable e) {
			if(e instanceof ExecutionException) {
				e = e.getCause();
			}
			Throwables.throwIfUnchecked(e);
			throw new RuntimeException(e);
		}
	}

	@Override
	public Promise<? extends NoticeResult> asyncNotice(NoticeCommand cmd) {
		return this.asyncNotice(cmd, null);
	}

	@Override
	public Promise<? extends NoticeResult> asyncNotice(NoticeCommand cmd, Object orderKey) {
		return this.asyncNotice(buildBaseNoticeCommand(cmd), orderKey);
	}
	
	private BaseNoticeCommand buildBaseNoticeCommand(NoticeCommand cmd) {
		if(cmd instanceof BaseNoticeCommand) {
			return (BaseNoticeCommand) cmd;
		}else {
			return new DefaultNoticeCommand(com.chopsticks.core.rocketmq.Const.DEFAULT_TOPIC, cmd.getMethod(), cmd.getBody());
		}
	}
	
	private BaseInvokeCommand buildBaseInvokeCommand(InvokeCommand cmd) {
		if(cmd instanceof BaseInvokeCommand) {
			return (BaseInvokeCommand) cmd;
		}else {
			return new DefaultInvokeCommand(com.chopsticks.core.rocketmq.Const.DEFAULT_TOPIC, cmd.getMethod(), cmd.getBody());
		}
	}
	
	@Override
	public NoticeResult notice(NoticeCommand cmd, Long delay, TimeUnit delayTimeUnit) {
		return this.notice(buildBaseNoticeCommand(cmd), delay, delayTimeUnit);
	}

	@Override
	public Promise<? extends NoticeResult> asyncNotice(NoticeCommand cmd, Long delay, TimeUnit delayTimeUnit) {
		return this.asyncNotice(buildBaseNoticeCommand(cmd), delay, delayTimeUnit);
	}
	
	
	public BaseNoticeResult notice(BaseNoticeCommand cmd, Long delay, TimeUnit delayTimeUnit) {
		try {
			return this.asyncNotice(cmd, delay, delayTimeUnit).get();
		}catch (Throwable e) {
			if(e instanceof ExecutionException) {
				e = e.getCause();
			}
			Throwables.throwIfUnchecked(e);
			throw new RuntimeException(e);
		}
	}
	
	public Promise<BaseNoticeResult> asyncNotice(final BaseNoticeCommand cmd, final Long delay, final TimeUnit delayTimeUnit) {
		checkArgument(started, "must be call method start");
		final GuavaPromise<BaseNoticeResult> promise = new GuavaPromise<BaseNoticeResult>();
		try {
			Message msg = buildDelayNoticeMessage(cmd, delay, delayTimeUnit);
			NoticeSendCallback callback = new NoticeSendCallback(promise);
			producer.send(msg, callback);
		}catch (Throwable e) {
			promise.setException(e);
		}
	
		return promise;
	}
	
	protected void setBatchExecuteIntervalMillis(long batchExecuteIntervalMillis) {
		this.batchExecuteIntervalMillis = batchExecuteIntervalMillis;
	}
	
	protected void setInvokable(boolean invokable) {
		this.invokable = invokable;
	}
	
	protected boolean isInvokable() {
		return invokable;
	}
	
	protected void resetNow(final MessageModel messageModel, final String instanceName, final String consumerGroup,
			final String topic) throws Throwable {

		DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(consumerGroup);
		consumer.setInstanceName(instanceName);
		consumer.setMessageModel(messageModel);
		consumer.start();

		Set<MessageQueue> mqs = null;
		try {
			mqs = consumer.fetchSubscribeMessageQueues(topic);
			if (mqs != null && !mqs.isEmpty()) {
				TreeSet<MessageQueue> mqsNew = new TreeSet<MessageQueue>(mqs);
				for (MessageQueue mq : mqsNew) {
					long offset = consumer.maxOffset(mq);
					if (offset >= 0) {
						consumer.updateConsumeOffset(mq, offset);
					}
				}
			}
		}catch (Throwable e) {
			if(!e.getMessage().contains(com.chopsticks.core.rocketmq.Const.ERROR_MSG_CAN_NOT_FIND_MESSAGE_QUEUE)) {
				Throwables.throwIfUnchecked(e);
				throw new RuntimeException(e);
			}
		}finally {
			if (mqs != null) {
				consumer.getDefaultMQPullConsumerImpl().getOffsetStore().persistAll(mqs);
			}
			consumer.shutdown();
		}
	}
	
	protected void realConsumeFromLastOffset(final MessageModel messageModel, final String instanceName, final String consumerGroup,
			final String topic) throws Throwable{
		DefaultMQPullConsumer consumer = new DefaultMQPullConsumer(consumerGroup);
		consumer.setInstanceName(instanceName);
		consumer.setMessageModel(messageModel);
		consumer.start();
		Set<MessageQueue> mqs = null;
		try {
			mqs = consumer.fetchSubscribeMessageQueues(topic);
			if (mqs != null && !mqs.isEmpty()) {
				TreeSet<MessageQueue> mqsNew = new TreeSet<MessageQueue>(mqs);
				for (MessageQueue mq : mqsNew) {
					long offset = consumer.getOffsetStore().readOffset(mq, ReadOffsetType.READ_FROM_STORE);
					long maxOffset = consumer.maxOffset(mq);
					if (maxOffset > 0 && offset <= 0) {
						consumer.updateConsumeOffset(mq, maxOffset);
					}
				}
			}
		}catch (Throwable e) {
			if(!e.getMessage().contains(com.chopsticks.core.rocketmq.Const.ERROR_MSG_CAN_NOT_FIND_MESSAGE_QUEUE)) {
				Throwables.throwIfUnchecked(e);
				throw new RuntimeException(e);
			}
		}finally {
			if (mqs != null) {
				consumer.getDefaultMQPullConsumerImpl().getOffsetStore().persistAll(mqs);
			}
			consumer.shutdown();
		}
	}
}
