package com.chopsticks.core.rocketmq.exception;

public class InvokeExecuteException extends RuntimeException{
	
	private static final long serialVersionUID = 1L;

	public InvokeExecuteException(String errMsg) {
		super(errMsg);
	}
}
