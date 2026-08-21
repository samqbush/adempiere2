package org.adempiere.phase3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.compiere.model.ModelValidator;
import org.compiere.model.PO;
import org.compiere.process.ProcessCall;

final class MetadataExtensionGraphValidator {

	private static final String DEFAULT_MODEL_PACKAGE = "org.adempiere.core.domains.models";
	private static final Pattern JAVA_PACKAGE =
		Pattern.compile("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*"
			+ "(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*");

	private final Connection connection;
	private final ClassLoader classLoader;
	private final List<MetadataFinding> findings = new ArrayList<>();
	private final Map<String, EntityDescriptor> entities = new LinkedHashMap<>();
	private int recordsChecked;

	MetadataExtensionGraphValidator(Connection connection, ClassLoader classLoader) {
		this.connection = connection;
		this.classLoader = classLoader;
	}

	MetadataValidationReport validate() throws SQLException {
		loadEntityDescriptors();
		validateProcesses();
		validateModelValidators();
		validateWorkflowAndProcessReferences();
		validateGeneratedModels();
		return new MetadataValidationReport(recordsChecked, findings);
	}

	Class<?> validateBinding(
		String check,
		String recordType,
		int recordId,
		String recordName,
		String entityType,
		String className,
		Class<?> requiredType) {

		try {
			Class<?> loaded = classLoader.loadClass(className);
			if (!requiredType.isAssignableFrom(loaded)) {
				add(check, recordType, recordId, recordName, entityType, className,
					"loaded type " + loaded.getName() + " does not implement/extend "
						+ requiredType.getName());
				return null;
			}
			if (loaded.isInterface() || Modifier.isAbstract(loaded.getModifiers())
				|| !Modifier.isPublic(loaded.getModifiers())) {
				add(check, recordType, recordId, recordName, entityType, className,
					"binding is not a public, instantiable concrete class");
				return null;
			}
			if (!Modifier.isPublic(loaded.getDeclaredConstructor().getModifiers())) {
				add(check, recordType, recordId, recordName, entityType, className,
					"no-argument constructor is not public");
				return null;
			}
			return loaded;
		}
		catch (ClassNotFoundException exception) {
			add(check, recordType, recordId, recordName, entityType, className,
				"class is not present on the Phase 3 runtime graph");
		}
		catch (NoSuchMethodException exception) {
			add(check, recordType, recordId, recordName, entityType, className,
				"class has no no-argument constructor required by the runtime loader");
		}
		catch (LinkageError error) {
			add(check, recordType, recordId, recordName, entityType, className,
				"class linkage failed: " + error.getClass().getSimpleName() + ": "
					+ error.getMessage());
		}
		return null;
	}

	private void loadEntityDescriptors() throws SQLException {
		query("""
			SELECT AD_EntityType_ID, EntityType, Name, ModelPackage, Classpath
			  FROM AD_EntityType
			 WHERE IsActive='Y'
			 ORDER BY AD_EntityType_ID
			""", result -> {
				int id = result.getInt("AD_EntityType_ID");
				String entityType = result.getString("EntityType");
				String name = result.getString("Name");
				String modelPackage = result.getString("ModelPackage");
				String classpath = result.getString("Classpath");
				recordsChecked++;
				if (entityType == null || entityType.isBlank()) {
					add("extension-descriptor", "AD_EntityType", id, name, entityType, null,
						"active descriptor has no EntityType key");
					return;
				}
				List<String> packages = splitDescriptor(modelPackage);
				for (String packageName : packages) {
					if (!JAVA_PACKAGE.matcher(packageName).matches()) {
						add("extension-descriptor", "AD_EntityType", id, name, entityType,
							packageName, "ModelPackage is not a valid Java package name");
					}
				}
				if (classpath != null && !classpath.isBlank()
					&& splitDescriptor(classpath).isEmpty()) {
					add("extension-descriptor", "AD_EntityType", id, name, entityType,
						classpath, "Classpath descriptor contains no usable entries");
				}
				EntityDescriptor previous = entities.put(entityType,
					new EntityDescriptor(id, name, packages));
				if (previous != null) {
					add("extension-descriptor", "AD_EntityType", id, name, entityType, null,
						"duplicates active descriptor ID " + previous.id());
				}
			});
	}

	private void validateProcesses() throws SQLException {
		query("""
			SELECT AD_Process_ID, Value, Name, Classname, EntityType
			  FROM AD_Process
			 WHERE IsActive='Y'
			   AND Classname IS NOT NULL
			   AND BTRIM(Classname) <> ''
			   AND LOWER(Classname) NOT LIKE '@script:%'
			 ORDER BY AD_Process_ID
			""", result -> {
				recordsChecked++;
				validateBinding(
					"process-class",
					"AD_Process",
					result.getInt("AD_Process_ID"),
					displayName(result, "Value", "Name"),
					result.getString("EntityType"),
					result.getString("Classname").trim(),
					ProcessCall.class);
			});
	}

	private void validateModelValidators() throws SQLException {
		query("""
			SELECT AD_ModelValidator_ID, Name, ModelValidationClass, EntityType
			  FROM AD_ModelValidator
			 WHERE IsActive='Y'
			   AND ModelValidationClass IS NOT NULL
			   AND BTRIM(ModelValidationClass) <> ''
			 ORDER BY AD_ModelValidator_ID
			""", result -> {
				recordsChecked++;
				validateBinding(
					"model-validator-class",
					"AD_ModelValidator",
					result.getInt("AD_ModelValidator_ID"),
					result.getString("Name"),
					result.getString("EntityType"),
					result.getString("ModelValidationClass").trim(),
					ModelValidator.class);
			});
	}

	private void validateWorkflowAndProcessReferences() throws SQLException {
		validateReference("""
			SELECT n.AD_WF_Node_ID AS Record_ID, n.Name AS Record_Name, n.EntityType,
			       n.AD_Process_ID AS Target_ID, p.Name AS Target_Name, p.IsActive AS Target_Active
			  FROM AD_WF_Node n
			  LEFT JOIN AD_Process p ON p.AD_Process_ID=n.AD_Process_ID
			 WHERE n.IsActive='Y' AND n.Action IN ('P','R')
			""", "AD_WF_Node", "AD_Process", "workflow-process-reference");
		validateReference("""
			SELECT n.AD_WF_Node_ID AS Record_ID, n.Name AS Record_Name, n.EntityType,
			       n.AD_Workflow_ID AS Target_ID, w.Name AS Target_Name, w.IsActive AS Target_Active
			  FROM AD_WF_Node n
			  LEFT JOIN AD_Workflow w ON w.AD_Workflow_ID=n.AD_Workflow_ID
			 WHERE n.IsActive='Y' AND n.Action='F'
			""", "AD_WF_Node", "AD_Workflow", "workflow-subworkflow-reference");
		validateReference("""
			SELECT p.AD_Process_ID AS Record_ID, p.Name AS Record_Name, p.EntityType,
			       p.AD_Workflow_ID AS Target_ID, w.Name AS Target_Name, w.IsActive AS Target_Active
			  FROM AD_Process p
			  LEFT JOIN AD_Workflow w ON w.AD_Workflow_ID=p.AD_Workflow_ID
			 WHERE p.IsActive='Y' AND p.AD_Workflow_ID IS NOT NULL
			   AND p.AD_Workflow_ID > 0
			""", "AD_Process", "AD_Workflow", "process-workflow-reference");
		validateReference("""
			SELECT s.AD_Scheduler_ID AS Record_ID, s.Name AS Record_Name,
			       NULL::varchar AS EntityType,
			       s.AD_Process_ID AS Target_ID, p.Name AS Target_Name, p.IsActive AS Target_Active
			  FROM AD_Scheduler s
			  LEFT JOIN AD_Process p ON p.AD_Process_ID=s.AD_Process_ID
			 WHERE s.IsActive='Y'
			""", "AD_Scheduler", "AD_Process", "scheduler-process-reference");
		validateReference("""
			SELECT n.AD_WF_Node_ID AS Record_ID, n.Name AS Record_Name, n.EntityType,
			       n.AD_Workflow_ID AS Target_ID, w.Name AS Target_Name, w.IsActive AS Target_Active
			  FROM AD_WF_Node n
			  LEFT JOIN AD_Workflow w ON w.AD_Workflow_ID=n.AD_Workflow_ID
			 WHERE n.IsActive='Y'
			""", "AD_WF_Node", "AD_Workflow", "workflow-node-parent-reference");
		validateReference("""
			SELECT tp.AD_Table_ID AS Record_ID, t.TableName AS Record_Name, tp.EntityType,
			       tp.AD_Process_ID AS Target_ID, p.Name AS Target_Name, p.IsActive AS Target_Active
			  FROM AD_Table_Process tp
			  LEFT JOIN AD_Table t ON t.AD_Table_ID=tp.AD_Table_ID
			  LEFT JOIN AD_Process p ON p.AD_Process_ID=tp.AD_Process_ID
			 WHERE tp.IsActive='Y'
			""", "AD_Table_Process", "AD_Process", "table-process-reference");
		validateReference("""
			SELECT m.AD_Menu_ID AS Record_ID, m.Name AS Record_Name, m.EntityType,
			       m.AD_Process_ID AS Target_ID, p.Name AS Target_Name, p.IsActive AS Target_Active
			  FROM AD_Menu m
			  LEFT JOIN AD_Process p ON p.AD_Process_ID=m.AD_Process_ID
			 WHERE m.IsActive='Y' AND m.AD_Process_ID IS NOT NULL AND m.AD_Process_ID > 0
			""", "AD_Menu", "AD_Process", "menu-process-reference");
		validateReference("""
			SELECT m.AD_Menu_ID AS Record_ID, m.Name AS Record_Name, m.EntityType,
			       m.AD_Workflow_ID AS Target_ID, w.Name AS Target_Name, w.IsActive AS Target_Active
			  FROM AD_Menu m
			  LEFT JOIN AD_Workflow w ON w.AD_Workflow_ID=m.AD_Workflow_ID
			 WHERE m.IsActive='Y' AND m.AD_Workflow_ID IS NOT NULL AND m.AD_Workflow_ID > 0
			""", "AD_Menu", "AD_Workflow", "menu-workflow-reference");
	}

	private void validateReference(
		String sql,
		String recordType,
		String targetType,
		String check) throws SQLException {

		query(sql, result -> {
			recordsChecked++;
			int targetId = result.getInt("Target_ID");
			String targetName = result.getString("Target_Name");
			String targetActive = result.getString("Target_Active");
			if (targetId <= 0) {
				add(check, recordType, result.getInt("Record_ID"),
					result.getString("Record_Name"), result.getString("EntityType"), null,
					"active record requires a " + targetType + " reference but Target_ID="
						+ targetId);
			}
			else if (targetName == null) {
				add(check, recordType, result.getInt("Record_ID"),
					result.getString("Record_Name"), result.getString("EntityType"), null,
					targetType + "[ID=" + targetId + "] does not exist");
			}
			else if (!"Y".equals(targetActive)) {
				add(check, recordType, result.getInt("Record_ID"),
					result.getString("Record_Name"), result.getString("EntityType"), null,
					"references inactive " + targetType + "[ID=" + targetId + ", name="
						+ targetName + "]");
			}
		});
	}

	private void validateGeneratedModels() throws SQLException {
		query("""
			SELECT t.AD_Table_ID, t.TableName, t.EntityType, e.Name AS Entity_Name
			  FROM AD_Table t
			  LEFT JOIN AD_EntityType e
			    ON e.EntityType=t.EntityType AND e.IsActive='Y'
			 WHERE t.IsActive='Y'
			   AND (t.TableName IN ('RV_WarehousePrice','RV_BPartner') OR t.IsView='N')
			   AND t.TableName NOT LIKE '%_Trl'
			   AND t.EntityType IN (
			       'FMS','A','C','D','CRM','PR','ECA01','ECA02','ECA03','ECA12',
			       'ECA22','ECA23','ECA34','ECA41','ECA42','EE01','EE02','EE03',
			       'EE04','EE05','EE06','EE07','EE08','EE09','EE12','FA')
			 ORDER BY t.AD_Table_ID
			""", result -> {
				recordsChecked++;
				int tableId = result.getInt("AD_Table_ID");
				String tableName = result.getString("TableName");
				String entityType = result.getString("EntityType");
				EntityDescriptor descriptor = entities.get(entityType);
				if (descriptor == null) {
					add("entity-package", "AD_Table", tableId, tableName, entityType, null,
						"active table has no active AD_EntityType descriptor");
					return;
				}
				List<String> packages = new ArrayList<>(descriptor.modelPackages());
				if (!packages.contains(DEFAULT_MODEL_PACKAGE)) {
					packages.add(DEFAULT_MODEL_PACKAGE);
				}
				validateGeneratedPair(tableId, tableName, entityType, packages);
			});
	}

	private void validateGeneratedPair(
		int tableId,
		String tableName,
		String entityType,
		List<String> packages) {

		List<String> attempts = new ArrayList<>();
		for (String packageName : packages) {
			String interfaceName = packageName + ".I_" + tableName;
			String modelName = packageName + ".X_" + tableName;
			attempts.add(interfaceName + " + " + modelName);
			try {
				Class<?> modelInterface = classLoader.loadClass(interfaceName);
				Class<?> modelClass = classLoader.loadClass(modelName);
				validateTableNameField(tableId, tableName, entityType, modelInterface);
				validateTableNameField(tableId, tableName, entityType, modelClass);
				if (!modelInterface.isAssignableFrom(modelClass)) {
					add("generated-model-binding", "AD_Table", tableId, tableName, entityType,
						modelName, "generated X_ class does not implement " + interfaceName);
				}
				if (!PO.class.isAssignableFrom(modelClass)) {
					add("generated-model-binding", "AD_Table", tableId, tableName, entityType,
						modelName, "generated X_ class does not extend " + PO.class.getName());
				}
				return;
			}
			catch (ClassNotFoundException exception) {
				// Try the next package declared by this entity descriptor.
			}
			catch (LinkageError error) {
				add("generated-model-binding", "AD_Table", tableId, tableName, entityType,
					modelName, "class linkage failed: " + error.getClass().getSimpleName()
						+ ": " + error.getMessage());
				return;
			}
		}
		add("generated-model-binding", "AD_Table", tableId, tableName, entityType,
			String.join(" | ", attempts),
			"no matching generated I_/X_ pair exists in the entity ModelPackage descriptor");
	}

	private void validateTableNameField(
		int tableId,
		String tableName,
		String entityType,
		Class<?> type) {

		try {
			Field field = type.getField("Table_Name");
			Object value = field.get(null);
			if (!tableName.equals(value)) {
				add("generated-table-binding", "AD_Table", tableId, tableName, entityType,
					type.getName(), "Table_Name is " + value + " instead of " + tableName);
			}
		}
		catch (ReflectiveOperationException exception) {
			add("generated-table-binding", "AD_Table", tableId, tableName, entityType,
				type.getName(), "cannot read public static Table_Name: "
					+ exception.getClass().getSimpleName());
		}
	}

	private void query(String sql, SqlConsumer consumer) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql);
			ResultSet result = statement.executeQuery()) {
			while (result.next()) {
				consumer.accept(result);
			}
		}
	}

	private void add(
		String check,
		String recordType,
		int recordId,
		String recordName,
		String entityType,
		String className,
		String detail) {

		findings.add(new MetadataFinding(
			check, recordType, recordId, recordName, entityType, className, detail));
	}

	private static List<String> splitDescriptor(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return Arrays.stream(value.split("[;,\\s]+"))
			.map(String::trim)
			.filter(token -> !token.isEmpty())
			.distinct()
			.toList();
	}

	private static String displayName(ResultSet result, String first, String second)
		throws SQLException {

		String value = result.getString(first);
		return value == null || value.isBlank() ? result.getString(second) : value;
	}

	private record EntityDescriptor(int id, String name, List<String> modelPackages) {
	}

	@FunctionalInterface
	private interface SqlConsumer {
		void accept(ResultSet result) throws SQLException;
	}
}
