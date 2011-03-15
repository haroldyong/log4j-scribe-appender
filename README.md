Log4j Scribe Appender
===

A Scribe appender for Log4j allowing log events to be sent to a local or remote Scribe instance. This is probably best used with an AsyncAppender wrapped around it and also with a backup appender defined for messages that are dropped.

History
---

[Alex Loddengaard](http://github.com/alexlod/scribe-log4j-appender)

 * original author

[Chris Goffinet](http://github.com/lenn0x/Scribe-log4j-Appender)

 * cleaned up code to work in non-Hadoop environments
 * added support to reconnect if it loses a connection or if Scribe goes away

[Josh Devins](http://github.com/joshdevins/Scribe-log4j-Appender)

 * reorganized and refactored
 * removed checked in libs and provided basic Maven pom
 * regenerated Thrift classes (just in case)
 * added better error reporting through log4j
 * added test cases

Building
---

You will need the following libraries installed in your Maven repository:

 * [Thrift](http://thrift.apache.org) - 0.6.0

Configuration
---

	log4j.rootLogger=INFO, stdout, scribe
	
	# stdout/console appender
	log4j.appender.stdout=org.apache.log4j.ConsoleAppender
	log4j.appender.stdout.layout=org.apache.log4j.PatternLayout
	log4j.appender.stdout.layout.ConversionPattern=%5p [%t] (%F:%L) - %m%n

	# Scribe appender
	log4j.appender.scribe=org.apache.log4j.net.ScribeAppender

	# do NOT use a trailing %n unless you want a newline to be transmitted to Scribe after every message
        # since you are sane and using Puppet or Chef to deploy configuration, you should also have that tool insert canonical hostname in the logging pattern
	log4j.appender.scribe.layout=org.apache.log4j.PatternLayout
	log4j.appender.scribe.layout.ConversionPattern=[fqdnFromPuppetOrChef] %d{ISO8601} %m

	# optional properties
	log4j.appender.scribe.category=application.appender.category
	log4j.appender.scribe.remoteHost=127.0.0.1
	log4j.appender.scribe.remotePort=1463
	log4j.appender.scribe.stackTraceDepth=1
