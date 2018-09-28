package com.chopsticks.core.modern.caller;

import java.util.concurrent.TimeUnit;

import com.chopsticks.core.caller.InvokeResult;
import com.chopsticks.core.caller.NoticeResult;

public interface ExtBean {

	public InvokeResult invoke(InvokeCommand cmd);

	public InvokeResult invoke(InvokeCommand cmd, long timeout, TimeUnit timeoutUnit);

	public NoticeResult notice(NoticeCommand cmd);

	public NoticeResult notice(NoticeCommand cmd, Object orderKey);

	public NoticeResult notice(NoticeCommand cmd, Long delay, TimeUnit delayTimeUnit);
}