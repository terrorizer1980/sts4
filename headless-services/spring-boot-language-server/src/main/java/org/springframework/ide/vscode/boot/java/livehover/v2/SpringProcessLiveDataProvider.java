/*******************************************************************************
 * Copyright (c) 2019 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.boot.java.livehover.v2;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.protocol.STS4LanguageClient;
import org.springframework.ide.vscode.commons.util.Assert;

/**
 * @author Martin Lippert
 */
public class SpringProcessLiveDataProvider {
	
	private final ConcurrentMap<String, SpringProcessLiveData> liveData;
	private final List<SpringProcessLiveDataChangeListener> listeners;
	private final SimpleLanguageServer server;
	
	public SpringProcessLiveDataProvider(SimpleLanguageServer server) {
		this.server = server;
		this.liveData = new ConcurrentHashMap<>();
		this.listeners = new CopyOnWriteArrayList<>();
	}
	
	public SpringProcessLiveData[] getLatestLiveData() {
		Collection<SpringProcessLiveData> values = this.liveData.values();
		return (SpringProcessLiveData[]) values.toArray(new SpringProcessLiveData[values.size()]);
	}
	
	/**
	 * add the process live data, if there is not already process live data associated with the process key 
	 * @return true, if the data was added (and was new), otherwise false
	 */
	public boolean add(String processKey, SpringProcessLiveData liveData) {
		SpringProcessLiveData oldData = this.liveData.putIfAbsent(processKey, liveData);
		if (oldData == null) {
			announceChangedLiveData();
			getClient().liveProcessConnected(processKey);
		}
		return oldData == null;
	}
	
	private STS4LanguageClient getClient() {
		STS4LanguageClient client = server.getClient();
		Assert.isLegal(client!=null, "Client is null. Language server not yet initialized?");
		return client;
	}

	public void remove(String processKey) {
		SpringProcessLiveData removed = this.liveData.remove(processKey);
		if (removed != null) {
			announceChangedLiveData();
			getClient().liveProcessDisconnected(processKey);
		}
	}
	
	public void update(String processKey, SpringProcessLiveData liveData) {
		this.liveData.put(processKey, liveData);
		announceChangedLiveData();
		getClient().liveProcessDataUpdated(processKey);
	}
	
	
	public void addLiveDataChangeListener(SpringProcessLiveDataChangeListener listener) {
		this.listeners.add(listener);
	}
	
	public void removeLiveDataChangeListener(SpringProcessLiveDataChangeListener listener) {
		this.listeners.remove(listener);
	}
	
	private void announceChangedLiveData() {
		SpringProcessLiveData[] latestLiveData = getLatestLiveData();
		SpringProcessLiveDataChangeEvent event = new SpringProcessLiveDataChangeEvent(latestLiveData);
		for (SpringProcessLiveDataChangeListener listener : this.listeners) {
			listener.liveDataChanged(event);
		}
	}
	
	SpringProcessLiveData getCurrent(String processKey) {
		return this.liveData.get(processKey);
	}

}
