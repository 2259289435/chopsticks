package com.chopsticks.core.rocketmq;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chopsticks.core.Client;
import com.chopsticks.core.exception.CoreException;
import com.chopsticks.core.handler.Handler;
import com.chopsticks.core.rocketmq.caller.DefaultCaller;
import com.chopsticks.core.rocketmq.exception.DefaultCoreException;
import com.chopsticks.core.rocketmq.handler.BaseHandler;
import com.chopsticks.core.rocketmq.handler.HandlerDelayNoticeListener;
import com.chopsticks.core.rocketmq.handler.HandlerInvokeListener;
import com.chopsticks.core.rocketmq.handler.HandlerNoticeListener;
import com.chopsticks.core.rocketmq.handler.HandlerOrderedNoticeListener;
import com.chopsticks.core.rocketmq.handler.impl.BaseHandlerWapper;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets; 

/**
 * 默认客户端实现
 * @author zilong.li
 *
 */
public class DefaultClient extends DefaultCaller implements Client{
	
	private static final Logger log = LoggerFactory.getLogger(DefaultClient.class);
	
	private volatile boolean started;
	
	private Set<BaseHandler> handlers;
	
	/**
	 *  <topic,Set<tag>>
	 */
	private Multimap<String, String> topicTags;
	
	/**
	 *  <topic + tag, baseHandler>
	 */
	private Map<String, BaseHandler> topicTagHandlers;
	
	private DefaultMQPushConsumer invokeConsumer;
	private DefaultMQPushConsumer noticeConsumer;
	private DefaultMQPushConsumer delayNoticeConsumer;
	private DefaultMQPushConsumer orderedNoticeConsumer;
	
	private boolean invokeExecutable = true;
	private boolean noticeExecutable = true;
	private boolean delayNoticeExecutable = true;
	private boolean orderedNoticeExecutable = true;
	
	private int invokeExecutableNum = 15;
	private int noticeExecutableNum = 10;
	private int delayNoticeExecutableNum = 10;
	private int orderedNoticeExecutableNum = 5;
	
	private int noticeExcecutableRetryCount = Integer.MAX_VALUE;
	private int delayNoticeExecutableRetryCount = Integer.MAX_VALUE;
	private int orderedNoticeExecutableRetryCount = Integer.MAX_VALUE;
	
	private long invokeMaxExecutableTime = TimeUnit.MINUTES.toMinutes(60);
	private long noticeMaxExecutableTime = TimeUnit.MINUTES.toMinutes(60);
	private long delayNoticeMaxExecutableTime = TimeUnit.MINUTES.toMinutes(60);
	private long orderedNoticeMaxExecutableTime = TimeUnit.MINUTES.toMinutes(60);
	
	private long invokeBeginExectableTime = Const.CLIENT_TIME.getNow();
	private long noticeBeginExecutableTime = -1L;
	private long delayNoticeBeginExecutableTime = -1L;
	private long orderedNoticeBeginExecutableTime = -1L;
	
	public DefaultClient(String groupName) {
		super(groupName);
	}
	
	@Override
	public void register(Set<? extends Handler> handlers) {
		this.handlers = buildBaseHandlers(handlers);
	}
	
	private Set<BaseHandler> buildBaseHandlers(Set<? extends Handler> handlers) {
		Set<BaseHandler> baseHandlers = Sets.newHashSet();
		for(Handler handler : handlers) {
			if(handler instanceof BaseHandler) {
				baseHandlers.add((BaseHandler)handler);
				baseHandlers.add(new BaseHandlerWapper(handler, ((BaseHandler) handler).getTopic() , Const.buildCustomTag(getGroupName(), ((BaseHandler) handler).getTag())));
			}else {
				baseHandlers.add(new BaseHandlerWapper(handler, Const.DEFAULT_TOPIC, handler.getMethod()));
				baseHandlers.add(new BaseHandlerWapper(handler, Const.DEFAULT_TOPIC, Const.buildCustomTag(getGroupName(), handler.getMethod())));
			}
		}
		return baseHandlers;
	}

	@Override
	public synchronized void shutdown() {
		if(started) {
			super.shutdown();
			if(invokeConsumer != null) {
				invokeConsumer.shutdown();
				invokeConsumer = null;
			}
			if(delayNoticeConsumer != null) {
				delayNoticeConsumer.shutdown();
				delayNoticeConsumer = null;
			}
			if(noticeConsumer != null){
				noticeConsumer.shutdown();
				noticeConsumer = null;
			}
			if(orderedNoticeConsumer != null) {
				orderedNoticeConsumer.shutdown();
				orderedNoticeConsumer = null;
			}
			started = false;
		}
	}
	
	@Override
	public synchronized void start() {
		if(!started) {
			super.start();
			buildTopicTagsAndTopicTagHandlers();
			if(!topicTags.isEmpty()) {
				addTest();
				try {
					invokeConsumer = buildAndStartInvokeCosumer();
					delayNoticeConsumer = buildAndStartDelayNoticeCosumer();
					noticeConsumer = buildAndStartNoticeCosumer();
					orderedNoticeConsumer = buildAndStartOrderedNoticeCosumer();
				}catch (Throwable e) {
					if(invokeConsumer != null) {
						invokeConsumer.shutdown();
					}
					if(delayNoticeConsumer != null) {
						delayNoticeConsumer.shutdown();
					}
					if(noticeConsumer != null) {
						noticeConsumer.shutdown();
					}
					if(orderedNoticeConsumer != null) {
						orderedNoticeConsumer.shutdown();
					}
					if(e instanceof CoreException) {
						throw (CoreException)e;
					}else {
						throw new DefaultCoreException(e);
					}
				}
			}
			started = true;
		}
	}
	
	private void addTest() {
		for(String topic : topicTags.keySet()) {
			EmptyHandler handler = new EmptyHandler(topic, Const.buildTestTag(getGroupName()));
			topicTags.put(handler.getTopic(), handler.getTag());
			topicTagHandlers.put(handler.getTopic() + handler.getTag(), handler);
		}
	}

	private void buildTopicTagsAndTopicTagHandlers() {
		topicTags = MultimapBuilder.hashKeys().hashSetValues().build();
		topicTagHandlers = Maps.newHashMap();
		if(handlers != null) {
			for(BaseHandler handler : handlers) {
				if(Strings.isNullOrEmpty(handler.getTopic()) 
				|| Strings.isNullOrEmpty(handler.getTag())) {
					log.warn("handler topic and tag cannot be null or empty : {}", handler);
					continue;
				}
				topicTags.put(buildSuccessTopic(handler.getTopic()), handler.getTag());
				topicTagHandlers.put(buildSuccessTopic(handler.getTopic()) + handler.getTag(), handler);
			}
		}
	}
	
	
	private DefaultMQPushConsumer buildAndStartOrderedNoticeCosumer() {
		Stopwatch watch = Stopwatch.createStarted();
		DefaultMQPushConsumer orderedNoticeConsumer = null;
		if(isOrderedNoticeExecutable()) {
			String groupName = Const.CONSUMER_PREFIX + getGroupName() + Const.ORDERED_NOTICE_CONSUMER_SUFFIX;
			orderedNoticeConsumer = new DefaultMQPushConsumer(groupName, true);
			orderedNoticeConsumer.setNamesrvAddr(getNamesrvAddr());
			orderedNoticeConsumer.setConsumeThreadMin(getOrderedNoticeExecutableNum());
			orderedNoticeConsumer.setConsumeThreadMax(getOrderedNoticeExecutableNum());
			orderedNoticeConsumer.setMessageModel(MessageModel.CLUSTERING);
			orderedNoticeConsumer.setMaxReconsumeTimes(getOrderedNoticeExecutableRetryCount());
			orderedNoticeConsumer.setConsumeMessageBatchMaxSize(1);
			orderedNoticeConsumer.setConsumeTimeout(getOrderedNoticeMaxExecutableTime());
			orderedNoticeConsumer.setSuspendCurrentQueueTimeMillis(TimeUnit.SECONDS.toMillis(5L));
			orderedNoticeConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
			HandlerOrderedNoticeListener listener = new HandlerOrderedNoticeListener(this, orderedNoticeConsumer, topicTags, topicTagHandlers);
			listener.setBeginExecutableTime(getOrderedNoticeBeginExecutableTime());
			orderedNoticeConsumer.registerMessageListener(listener);
			orderedNoticeConsumer.setPullThresholdSizeForTopic(10);
			orderedNoticeConsumer.setPullThresholdForTopic(200);
			try {
				Set<String> topics = Sets.newHashSet();
				for(Entry<String, Collection<String>> entry: topicTags.asMap().entrySet()) {
					String topic = entry.getKey();
					Collection<String> tags = entry.getValue();
					topic = buildOrderedNoticeTopic(topic);
					topics.add(topic);
					if(tags.contains(Const.ALL_TAGS)) {
						orderedNoticeConsumer.subscribe(topic, Const.ALL_TAGS);
					}else {
						Set<String> newTags = Sets.newHashSet(tags);
						for(Iterator<String> iter = newTags.iterator(); iter.hasNext();) {
							String tag = Const.getOriginTag(getGroupName(), iter.next());
							if(!topicTagHandlers.get(buildSuccessTopic(entry.getKey()) + tag).isSupportOrderedNotice()) {
								iter.remove();
							}
						}
						orderedNoticeConsumer.subscribe(topic, Joiner.on("||").join(tags));
					}
				}
				createTopics(topics);
				checkConsumerSubscription(orderedNoticeConsumer);
				beforeOrderedNoticeConsumerStart(orderedNoticeConsumer);
				orderedNoticeConsumer.start();
				orderedNoticeConsumer = Const.buildConsumer(orderedNoticeConsumer);
				for(String topic : topics) {
					orderedNoticeConsumer.fetchSubscribeMessageQueues(topic);
				}
				log.trace("{} orderedNoticeConsumer start time : {} s", getGroupName(), watch.elapsed(TimeUnit.SECONDS));
			}catch (Throwable e) {
				if(orderedNoticeConsumer != null) {
					orderedNoticeConsumer.shutdown();
				}
				if(e instanceof CoreException) {
					throw (CoreException)e;
				}else {
					throw new DefaultCoreException(e);
				}
			}
		}
		return orderedNoticeConsumer;
	}
	
	private DefaultMQPushConsumer buildAndStartNoticeCosumer() {
		Stopwatch watch = Stopwatch.createStarted();
		DefaultMQPushConsumer noticeConsumer = null;
		if(isNoticeExecutable()) {
			String groupName = Const.CONSUMER_PREFIX + getGroupName() + Const.NOTICE_CONSUMER_SUFFIX;
			noticeConsumer = new DefaultMQPushConsumer(groupName, true);
			noticeConsumer.setNamesrvAddr(getNamesrvAddr());
			noticeConsumer.setConsumeThreadMin(getNoticeExecutableNum());
			noticeConsumer.setConsumeThreadMax(getNoticeExecutableNum());
			noticeConsumer.setMessageModel(MessageModel.CLUSTERING);
			noticeConsumer.setMaxReconsumeTimes(getNoticeExcecutableRetryCount());
			noticeConsumer.setConsumeMessageBatchMaxSize(1);
			noticeConsumer.setConsumeTimeout(getNoticeMaxExecutableTime());
			noticeConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
			HandlerNoticeListener listener = new HandlerNoticeListener(this, noticeConsumer, topicTags, topicTagHandlers);
			listener.setBeginExecutableTime(getNoticeBeginExecutableTime());
			noticeConsumer.registerMessageListener(listener);
			noticeConsumer.setPullThresholdSizeForTopic(10);
			noticeConsumer.setPullThresholdForTopic(200);
			try {
				Set<String> topics = Sets.newHashSet();
				for(Entry<String, Collection<String>> entry: topicTags.asMap().entrySet()) {
					String topic = entry.getKey();
					Collection<String> tags = entry.getValue();
					topic = buildNoticeTopic(topic);
					topics.add(topic);
					if(tags.contains(Const.ALL_TAGS)) {
						noticeConsumer.subscribe(topic, Const.ALL_TAGS);
					}else {
						Set<String> newTags = Sets.newHashSet(tags);
						for(Iterator<String> iter = newTags.iterator(); iter.hasNext();) {
							String tag = Const.getOriginTag(getGroupName(), iter.next());
							if(!topicTagHandlers.get(buildSuccessTopic(entry.getKey()) + tag).isSupportNotice()) {
								iter.remove();
							}
						}
						noticeConsumer.subscribe(topic, Joiner.on("||").join(tags));
					}
				}
				createTopics(topics);
				checkConsumerSubscription(noticeConsumer);
				beforeNoticeConsumerStart(noticeConsumer);
				noticeConsumer.start();
				noticeConsumer = Const.buildConsumer(noticeConsumer);
				for(String topic : topics) {
					noticeConsumer.fetchSubscribeMessageQueues(topic);
				}
				log.trace("{} noticeConsumer start time : {} s", getGroupName(), watch.elapsed(TimeUnit.SECONDS));
			}catch (Throwable e) {
				if(noticeConsumer != null) {
					noticeConsumer.shutdown();
				}
				if(e instanceof CoreException) {
					throw (CoreException)e;
				}else {
					throw new DefaultCoreException(e);
				}
			}
		}
		return noticeConsumer;
	}
	
	private DefaultMQPushConsumer buildAndStartDelayNoticeCosumer() {
		Stopwatch watch = Stopwatch.createStarted();
		DefaultMQPushConsumer delayNoticeConsumer = null;
		if(isDelayNoticeExecutable()) {
			String groupName = Const.CONSUMER_PREFIX + getGroupName() + Const.DELAY_NOTICE_CONSUMER_SUFFIX;
			delayNoticeConsumer = new DefaultMQPushConsumer(groupName, true);
			delayNoticeConsumer.setNamesrvAddr(getNamesrvAddr());
			delayNoticeConsumer.setConsumeThreadMin(getDelayNoticeExecutableNum());
			delayNoticeConsumer.setConsumeThreadMax(getDelayNoticeExecutableNum());
			delayNoticeConsumer.setMessageModel(MessageModel.CLUSTERING);
			delayNoticeConsumer.setMaxReconsumeTimes(getDelayNoticeExecutableRetryCount());
			delayNoticeConsumer.setConsumeMessageBatchMaxSize(1);
			delayNoticeConsumer.setConsumeTimeout(getDelayNoticeMaxExecutableTime());
			delayNoticeConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
			HandlerDelayNoticeListener listener = new HandlerDelayNoticeListener(this, delayNoticeConsumer, topicTags, topicTagHandlers);
			listener.setBeginExecutableTime(getDelayNoticeBeginExecutableTime());
			delayNoticeConsumer.registerMessageListener(listener);
			delayNoticeConsumer.setPullThresholdSizeForTopic(10);
			delayNoticeConsumer.setPullThresholdForTopic(200);
			try {
				Set<String> topics = Sets.newHashSet();
				for(Entry<String, Collection<String>> entry: topicTags.asMap().entrySet()) {
					String topic = entry.getKey();
					Collection<String> tags = entry.getValue();
					topic = buildDelayNoticeTopic(topic);
					topics.add(topic);
					if(tags.contains(Const.ALL_TAGS)) {
						delayNoticeConsumer.subscribe(topic, Const.ALL_TAGS);
					}else {
						Set<String> newTags = Sets.newHashSet(tags);
						for(Iterator<String> iter = newTags.iterator(); iter.hasNext();) {
							String tag = Const.getOriginTag(getGroupName(), iter.next());
							if(!topicTagHandlers.get(buildSuccessTopic(entry.getKey()) + tag).isSupportDelayNotice()) {
								iter.remove();
							}
						}
						delayNoticeConsumer.subscribe(topic, Joiner.on("||").join(tags));
					}
				}
				createTopics(topics);
				checkConsumerSubscription(delayNoticeConsumer);
				beforeNoticeConsumerStart(delayNoticeConsumer);
				delayNoticeConsumer.start();
				delayNoticeConsumer = Const.buildConsumer(delayNoticeConsumer);
				for(String topic : topics) {
					delayNoticeConsumer.fetchSubscribeMessageQueues(topic);
				}
				log.trace("{} delayNoticeConsumer start time : {} s", getGroupName(), watch.elapsed(TimeUnit.SECONDS));
			}catch (Throwable e) {
				if(delayNoticeConsumer != null) {
					delayNoticeConsumer.shutdown();
				}
				if(e instanceof CoreException) {
					throw (CoreException)e;
				}else {
					throw new DefaultCoreException(e);
				}
			}
		}
		return delayNoticeConsumer;
	}
	
	protected void beforeNoticeConsumerStart(DefaultMQPushConsumer noticeConsumer) {}
	protected void beforeDelayNoticeConsumerStart(DefaultMQPushConsumer delayNoticeConsumer) {}
	protected void beforeOrderedNoticeConsumerStart(DefaultMQPushConsumer orderedNoticeConsumer) {}
	protected void beforeInvokeConsumerStart(DefaultMQPushConsumer invokeConsumer) {}
	
	private DefaultMQPushConsumer buildAndStartInvokeCosumer() {
		DefaultMQPushConsumer invokeConsumer = null;
		Stopwatch watch = Stopwatch.createStarted();
		if(isInvokeExecutable()) {
			String groupName = Const.CONSUMER_PREFIX + getGroupName() + Const.INVOKE_CONSUMER_SUFFIX;
			invokeConsumer = new DefaultMQPushConsumer(groupName);
			invokeConsumer.setNamesrvAddr(getNamesrvAddr());
			invokeConsumer.setConsumeThreadMin(getInvokeExecutableNum());
			invokeConsumer.setConsumeThreadMax(getInvokeExecutableNum());
			invokeConsumer.setMessageModel(MessageModel.CLUSTERING);
			invokeConsumer.setMaxReconsumeTimes(0);
			invokeConsumer.setConsumeTimeout(getInvokeMaxExecutableTime());
			invokeConsumer.setConsumeMessageBatchMaxSize(1);
			invokeConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
			HandlerInvokeListener listener = new HandlerInvokeListener(this, topicTagHandlers);
			listener.setBeginExecutableTime(getInvokeBeginExectableTime());
			invokeConsumer.registerMessageListener(listener);
			invokeConsumer.setPullThresholdSizeForTopic(10);
			invokeConsumer.setPullThresholdForTopic(200);
			try {
				Set<String> topics = Sets.newHashSet();
				for(Entry<String, Collection<String>> entry: topicTags.asMap().entrySet()) {
					String topic = entry.getKey();
					Collection<String> tags = entry.getValue();
					topic = buildInvokeTopic(topic);
					topics.add(topic);
					if(tags.contains(Const.ALL_TAGS)) {
						invokeConsumer.subscribe(topic, Const.ALL_TAGS);
					}else {
						Set<String> newTags = Sets.newHashSet(tags);
						for(Iterator<String> iter = newTags.iterator(); iter.hasNext();) {
							String tag = Const.getOriginTag(getGroupName(), iter.next());
							if(!topicTagHandlers.get(buildSuccessTopic(entry.getKey()) + tag).isSupportInvoke()) {
								iter.remove();
							}
						}
						invokeConsumer.subscribe(topic, Joiner.on("||").join(newTags));
					}
				}
				
				createTopics(topics);
				checkConsumerSubscription(invokeConsumer);
				beforeInvokeConsumerStart(invokeConsumer);
				invokeConsumer.start();
				invokeConsumer = Const.buildConsumer(invokeConsumer);
				for(String topic : topics) {
					invokeConsumer.fetchSubscribeMessageQueues(topic);
				}
				log.trace("{} invokeConsumer start time : {} s", getGroupName(), watch.elapsed(TimeUnit.SECONDS));
			}catch (Throwable e) {
				if(invokeConsumer != null) {
					invokeConsumer.shutdown();
				}
				if(e instanceof CoreException) {
					throw (CoreException)e;
				}else {
					throw new DefaultCoreException(e);
				}
			}
		}
		return invokeConsumer;
	}
	
	public void setInvokeExecutable(boolean invokeExecutable) {
		this.invokeExecutable = invokeExecutable;
	}
	protected boolean isInvokeExecutable() {
		return invokeExecutable;
	}
	public void setNoticeExecutable(boolean noticeExecutable) {
		this.noticeExecutable = noticeExecutable;
	}
	protected boolean isNoticeExecutable() {
		return noticeExecutable;
	}
	public void setOrderedNoticeExecutable(boolean orderedNoticeExecutable) {
		this.orderedNoticeExecutable = orderedNoticeExecutable;
	}
	protected boolean isOrderedNoticeExecutable() {
		return orderedNoticeExecutable;
	}
	protected int getInvokeExecutableNum() {
		return invokeExecutableNum;
	}
	public void setInvokeExecutableNum(int invokeExecutableNum) {
		this.invokeExecutableNum = invokeExecutableNum;
	}
	protected int getNoticeExecutableNum() {
		return noticeExecutableNum;
	}
	public void setNoticeExecutableNum(int noticeExecutableNum) {
		this.noticeExecutableNum = noticeExecutableNum;
	}
	protected int getOrderedNoticeExecutableNum() {
		return orderedNoticeExecutableNum;
	}
	public void setOrderedNoticeExecutableNum(int orderedNoticeExecutableNum) {
		this.orderedNoticeExecutableNum = orderedNoticeExecutableNum;
	}
	protected boolean isDelayNoticeExecutable() {
		return delayNoticeExecutable;
	}
	public void setDelayNoticeExecutable(boolean delayNoticeExecutable) {
		this.delayNoticeExecutable = delayNoticeExecutable;
	}
	protected int getDelayNoticeExecutableNum() {
		return delayNoticeExecutableNum;
	}
	
	public void setDelayNoticeExecutableNum(int delayNoticeExecutableNum) {
		this.delayNoticeExecutableNum = delayNoticeExecutableNum;
	}
	protected int getNoticeExcecutableRetryCount() {
		return noticeExcecutableRetryCount;
	}
	public void setNoticeExcecutableRetryCount(int noticeExcecutableRetryCount) {
		this.noticeExcecutableRetryCount = noticeExcecutableRetryCount;
	}
	protected int getDelayNoticeExecutableRetryCount() {
		return delayNoticeExecutableRetryCount;
	}
	public void setDelayNoticeExecutableRetryCount(int delayNoticeExecutableRetryCount) {
		this.delayNoticeExecutableRetryCount = delayNoticeExecutableRetryCount;
	}
	protected int getOrderedNoticeExecutableRetryCount() {
		return orderedNoticeExecutableRetryCount;
	}
	public void setOrderedNoticeExecutableRetryCount(int orderedNoticeExecutableRetryCount) {
		this.orderedNoticeExecutableRetryCount = orderedNoticeExecutableRetryCount;
	}
	public void setRetryCount(int retryCount) {
		setNoticeExcecutableRetryCount(retryCount);
		setDelayNoticeExecutableRetryCount(retryCount);
		setOrderedNoticeExecutableRetryCount(retryCount);
	}
	protected long getNoticeMaxExecutableTime() {
		return noticeMaxExecutableTime;
	}
	public void setNoticeMaxExecutableTime(long noticeMaxExecutableTime) {
		this.noticeMaxExecutableTime = noticeMaxExecutableTime;
	}
	protected long getDelayNoticeMaxExecutableTime() {
		return delayNoticeMaxExecutableTime;
	}
	public void setDelayNoticeMaxExecutableTime(long delayNoticeMaxExecutableTime) {
		this.delayNoticeMaxExecutableTime = delayNoticeMaxExecutableTime;
	}
	protected long getOrderedNoticeMaxExecutableTime() {
		return orderedNoticeMaxExecutableTime;
	}
	public void setOrderedNoticeMaxExecutableTime(long orderedNoticeMaxExecutableTime) {
		this.orderedNoticeMaxExecutableTime = orderedNoticeMaxExecutableTime;
	}
	protected long getInvokeMaxExecutableTime() {
		return invokeMaxExecutableTime;
	}
	public void setInvokeMaxExecutableTime(long invokeMaxExecutableTime) {
		this.invokeMaxExecutableTime = invokeMaxExecutableTime;
	}
	protected long getInvokeBeginExectableTime() {
		return invokeBeginExectableTime;
	}
	protected void setInvokeBeginExectableTime(long invokeBeginExectableTime) {
		this.invokeBeginExectableTime = invokeBeginExectableTime;
	}
	protected long getNoticeBeginExecutableTime() {
		return noticeBeginExecutableTime;
	}
	public void setNoticeBeginExecutableTime(long noticeBeginExecutableTime) {
		this.noticeBeginExecutableTime = noticeBeginExecutableTime;
	}
	protected long getDelayNoticeBeginExecutableTime() {
		return delayNoticeBeginExecutableTime;
	}
	public void setDelayNoticeBeginExecutableTime(long delayNoticeBeginExecutableTime) {
		this.delayNoticeBeginExecutableTime = delayNoticeBeginExecutableTime;
	}
	protected long getOrderedNoticeBeginExecutableTime() {
		return orderedNoticeBeginExecutableTime;
	}
	public void setOrderedNoticeBeginExecutableTime(long orderedNoticeBeginExecutableTime) {
		this.orderedNoticeBeginExecutableTime = orderedNoticeBeginExecutableTime;
	}
	public void setMaxExecutableTime(long maxExecutableTime) {
		setInvokeMaxExecutableTime(maxExecutableTime);
		setNoticeMaxExecutableTime(maxExecutableTime);
		setDelayNoticeMaxExecutableTime(maxExecutableTime);
		setOrderedNoticeMaxExecutableTime(maxExecutableTime);
	}
}
