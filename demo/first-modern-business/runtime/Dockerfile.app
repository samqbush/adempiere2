ARG JAVA_IMAGE
FROM ${JAVA_IMAGE}

RUN groupadd --system adempiere \
    && useradd --system --gid adempiere --home-dir /opt/Adempiere \
      --shell /usr/sbin/nologin adempiere

COPY product/Adempiere /opt/Adempiere
COPY tomcat9 /opt/tomcat
COPY runtime/*.sh runtime/demo-database-tool /opt/demo/
COPY runtime/src /opt/demo/src

RUN chmod 0755 /opt/demo/*.sh /opt/demo/demo-database-tool \
    && mkdir -p /opt/demo/artifacts /opt/demo/classes /run/adempiere-demo \
    && cp /opt/Adempiere/lib/webui.war /opt/demo/artifacts/webui-routed.war \
    && javac -encoding UTF-8 -cp '/opt/Adempiere/lib/*' \
      -d /opt/demo/classes /opt/demo/src/org/adempiere/demo/*.java \
    && rm -rf /opt/demo/src \
    && chown -R adempiere:adempiere \
      /opt/Adempiere /opt/tomcat /opt/demo /run/adempiere-demo

WORKDIR /opt/Adempiere
EXPOSE 8888

ENTRYPOINT ["/opt/demo/start-application.sh"]
