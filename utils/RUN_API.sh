#!/bin/sh
# ADempiere isolated SOAP API start

if [ -n "$ADEMPIERE_HOME" ]; then
	cd "$ADEMPIERE_HOME/utils" || exit 1
fi

. ./myEnvironment.sh Server

API_HOME="$ADEMPIERE_HOME/tomcat10-api"
if [ ! -x "$API_HOME/bin/startup.sh" ]; then
	echo "ADempiere SOAP API runtime is not installed at $API_HOME" >&2
	exit 1
fi

export CATALINA_HOME="$API_HOME"
export CATALINA_BASE="$API_HOME"
export CATALINA_PID="$API_HOME/temp/tomcat.pid"
"$API_HOME/bin/startup.sh"
