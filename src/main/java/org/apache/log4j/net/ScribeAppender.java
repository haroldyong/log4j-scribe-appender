/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.log4j.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;

import com.facebook.scribe.thrift.LogEntry;
import com.facebook.scribe.thrift.ResultCode;
import com.facebook.scribe.thrift.scribe.Client;

/**
 * A basic log4j appender for sending log messages to a remote Scribe instance. The logic in
 * {@link #append(LoggingEvent)} will drop any log events that fail to be sent to Scribe. All failures are handled by
 * log4j error handling mechanism which should log to the backup appender if defined or STDERR if there is no backup
 * appender.
 * 
 * <p>
 * This is based on previous Scribe appenders as well as the built-in log4j appenders like {@link JMSAppender} and
 * {@link SyslogAppender}.
 * </p>
 * 
 * @see http://github.com/alexlod/scribe-log4j-appender
 * @see http://github.com/lenn0x/Scribe-log4j-Appender
 * 
 * @author Josh Devins
 */
public class ScribeAppender extends AppenderSkeleton {

    public static final String DEFAULT_REMOTE_HOST = "127.0.0.1";

    public static final int DEFAULT_REMOTE_PORT = 1463;

    private static final String DEFAULT_CATEGORY = "default";

    private String remoteHost = DEFAULT_REMOTE_HOST;

    private int remotePort = DEFAULT_REMOTE_PORT;

    private String category = DEFAULT_CATEGORY;

    private String localHostname;

    private Client client;

    private TFramedTransport transport;

    /**
     * Appends a log message to remote Scribe server. This is currently made thread safe by synchronizing this method,
     * however this is not very efficient and should be refactored.
     * 
     * TODO: Refactor for better effeciency and thread safety
     */
    @Override
    public synchronized void append(final LoggingEvent event) {

        boolean connected = connectIfNeeded();
        if (!connected) {
            return;
        }

        findAndSetLocalHostnameIfNeeded();

        try {

            String stackTrace = null;
            if (event.getThrowableInformation() != null) {

                StringBuilder sb = new StringBuilder();
                String[] stackTraceArray = event.getThrowableInformation().getThrowableStrRep();
                for (int i = 0; i < stackTraceArray.length; i++) {

                    sb.append(stackTraceArray[i]);

                    if (i > stackTraceArray.length - 1) {
                        // newlines will mess up processing in Hadoop if we assume each log entry is on a new line
                        sb.append('\t');
                    }
                }

                stackTrace = sb.toString();
            }

            // build log message to send with or without stack trace
            String message = String.format("[%s] %s (%s)", localHostname, layout.format(event), stackTrace);

            // log it to the client
            List<LogEntry> logEntries = new ArrayList<LogEntry>(1);
            logEntries.add(new LogEntry(category, message));

            ResultCode resultCode = client.Log(logEntries);

            // drop the message if Scribe can't handle it, this should end up in the backup appender
            if (ResultCode.TRY_LATER == resultCode) {

                // nicely formatted for batch processing
                getErrorHandler().error("TRY_LATER [" + message + "]");
            }

        } catch (TException e) {
            transport.close();
            handleError("TException on log attempt", e);

        } catch (Exception e) {
            handleError("Unhandled Exception on log attempt", e);
        }
    }

    /**
     * Close transport if open.
     */
    @Override
    public synchronized void close() {

        if (isConnected()) {
            transport.close();
        }
    }

    public synchronized boolean isConnected() {
        return transport != null && transport.isOpen();
    }

    public boolean requiresLayout() {
        return true;
    }

    public void setCategory(final String category) {
        this.category = category;
    }

    public void setLocalHostname(final String localHostname) {
        this.localHostname = localHostname;
    }

    public void setPort(final int remotePort) {
        this.remotePort = remotePort;
    }

    public void setRemoteHost(final String remoteHost) {
        this.remoteHost = remoteHost;
    }

    /**
     * Connect to Scribe if not open, reconnecting if a previous connection has failed.
     * 
     * @return connection success
     */
    private boolean connectIfNeeded() {

        if (isConnected()) {
            return true;
        }

        // connection was dropped, needs to be reopened
        if (transport != null && !transport.isOpen()) {
            transport.close();
        }

        try {
            establishConnection();
            return true;

        } catch (TTransportException e) {
            handleError("TTransportException on connect", e);

        } catch (UnknownHostException e) {
            handleError("UnknownHostException on connect", e);

        } catch (IOException e) {
            handleError("IOException on connect", e);

        } catch (Exception e) {
            handleError("Unhandled Exception on connect", e);
        }

        return false;
    }

    /**
     * Thrift boilerplate connection code. No error handling is attempted and all excetions are passed back up.
     */
    private void establishConnection() throws TTransportException, UnknownHostException, IOException {

        TSocket sock = new TSocket(new Socket(remoteHost, remotePort));
        transport = new TFramedTransport(sock);

        TBinaryProtocol protocol = new TBinaryProtocol(transport, false, false);
        client = new Client(protocol, protocol);
    }

    /**
     * If no {@link #localHostname} has been set, this will attempt to set it.
     */
    private void findAndSetLocalHostnameIfNeeded() {

        if (localHostname == null || localHostname.isEmpty()) {
            try {
                localHostname = InetAddress.getLocalHost().getCanonicalHostName();
            } catch (UnknownHostException e) {
                // can't get hostname
            }
        }
    }

    private void handleError(final String failure, final Exception e) {

        // error code is not used
        getErrorHandler().error("Failure in ScribeAppender: name=[" + name + "], failure=[" + failure + "]", e, 0);
    }
}