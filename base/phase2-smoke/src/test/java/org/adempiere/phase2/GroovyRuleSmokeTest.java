package org.adempiere.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import org.compiere.model.MRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(Phase2SmokeTag.NAME)
class GroovyRuleSmokeTest {

	@BeforeAll
	static void bootstrapRuntime() {
		Phase2RuntimeBootstrap.bootstrapServerRuntime();
	}

	@Test
	void resolvesOneGroovyProviderAndEvaluatesTypedMRuleExpression() throws Exception {
		MRule rule = new MRule(new Properties(), 0, null);
		rule.setRuleType(MRule.RULETYPE_JSR223ScriptingAPIs);
		rule.setEventType(MRule.EVENTTYPE_Process);
		rule.setValue(Phase2SmokeConfig.config("phase2.required.groovy.engine") + ":phase2DisposableRuntime");
		rule.setScript("return (G_BaseValue as Integer) + (A_Increment as Integer)");

		ScriptEngineManager manager = new ScriptEngineManager();
		List<ScriptEngineFactory> groovyFactories = manager.getEngineFactories()
			.stream()
			.filter(factory -> factory.getNames()
				.stream()
				.anyMatch(name -> name.equalsIgnoreCase(rule.getEngineName())))
			.collect(Collectors.toList());
		assertEquals(1, groovyFactories.size(),
			() -> "Expected exactly one Groovy JSR-223 provider but found " + groovyFactories);

		ScriptEngine engine = rule.getScriptEngine();
		assertNotNull(engine, "MRule did not resolve a Groovy script engine on the JDK 21 smoke runtime");

		Properties context = new Properties();
		context.put("#BaseValue", Integer.valueOf(7));
		MRule.setContext(engine, context, 0);
		engine.put(MRule.ARGUMENTS_PREFIX + "Increment", Integer.valueOf(4));

		Object result = engine.eval(rule.getScript());
		assertTrue(result instanceof Number,
			() -> "Expected Groovy to return a numeric result but got " + result + " (" +
				(result == null ? "null" : result.getClass().getName()) + ')');
		assertEquals(11, ((Number) result).intValue(),
			"Groovy-backed MRule expression returned the wrong typed numeric result");
	}
}
