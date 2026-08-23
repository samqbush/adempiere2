#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 6 ]]; then
	echo "Usage: prepare-operation-scenarios.sh <host> <port> <database> <user> <password> <database-marker>" >&2
	exit 64
fi

db_host=$1
db_port=$2
db_name=$3
db_user=$4
db_password=$5
database_marker=$6

if [[ ! "$db_host" =~ ^(127\.0\.0\.1|localhost|::1)$ ||
		"$db_name" != "adempiere_phase3_ci" ||
		"$db_user" != "adempiere_phase3_ci" ]]; then
	echo "Refusing to prepare scenarios outside the exact local Phase 3 database target." >&2
	exit 65
fi

run_psql() {
	PGPASSWORD=$db_password psql \
		--host="$db_host" \
		--port="$db_port" \
		--username="$db_user" \
		--dbname="$db_name" \
		--tuples-only \
		--no-align \
		--set=ON_ERROR_STOP=1 \
		"$@"
}

actual_marker=$(run_psql --command="
	SELECT shobj_description(oid, 'pg_database')
	FROM pg_database
	WHERE datname = current_database()")
if [[ "$actual_marker" != "$database_marker" ]]; then
	echo "Refusing to modify unmarked database $db_name." >&2
	exit 65
fi

encrypted=$(run_psql --command="
	SELECT IsEncrypted
	FROM AD_Column
	WHERE AD_Column_ID = 417")
if [[ "$encrypted" != "N" ]]; then
	echo "The deterministic POS fixture requires the plaintext-password seed." >&2
	exit 65
fi

updated=$(run_psql --command="
	WITH changed AS (
		UPDATE AD_User
		SET Password = 'phase4-pos-secret'
		WHERE Name = 'GardenAdmin'
		RETURNING 1
	)
	SELECT count(*) FROM changed")
if [[ "$updated" != "1" ]]; then
	echo "Expected exactly one GardenAdmin fixture row, updated $updated." >&2
	exit 65
fi

run_psql <<'SQL'
BEGIN;

DELETE FROM WS_WebServiceFieldInput
WHERE WS_WebServiceType_ID BETWEEN 59000 AND 59003;
DELETE FROM WS_WebServiceFieldOutput
WHERE WS_WebServiceType_ID BETWEEN 59000 AND 59003;
DELETE FROM WS_WebService_Para
WHERE WS_WebServiceType_ID BETWEEN 59000 AND 59003;
DELETE FROM WS_WebServiceTypeAccess
WHERE WS_WebServiceType_ID BETWEEN 59000 AND 59003;
DELETE FROM WS_WebServiceType
WHERE WS_WebServiceType_ID BETWEEN 59000 AND 59003;

INSERT INTO WS_WebServiceType (
	AD_Client_ID, AD_Org_ID, AD_Table_ID, Created, CreatedBy, Description,
	IsActive, Name, Updated, UpdatedBy, Value, WS_WebService_ID,
	WS_WebServiceMethod_ID, WS_WebServiceType_ID)
VALUES
	(11, 0, 291, now(), 100, 'Phase 4 read contract fixture', 'Y',
		'Phase 4 Read BPartner', now(), 100, 'Phase4ReadBPartner', 50001, 50027, 59000),
	(11, 0, 291, now(), 100, 'Phase 4 query contract fixture', 'Y',
		'Phase 4 Query BPartner', now(), 100, 'Phase4QueryBPartner', 50001, 50028, 59001),
	(11, 0, 291, now(), 100, 'Phase 4 update contract fixture', 'Y',
		'Phase 4 Update BPartner', now(), 100, 'Phase4UpdateBPartner', 50001, 50025, 59002),
	(11, 0, 291, now(), 100, 'Phase 4 delete contract fixture', 'Y',
		'Phase 4 Delete BPartner', now(), 100, 'Phase4DeleteBPartner', 50001, 50026, 59003);

INSERT INTO WS_WebServiceTypeAccess (
	AD_Client_ID, AD_Org_ID, AD_Role_ID, Created, CreatedBy, IsActive,
	IsReadWrite, Updated, UpdatedBy, WS_WebServiceType_ID)
SELECT 11, 0, 50004, now(), 100, 'Y', 'Y', now(), 100, fixture_id
FROM generate_series(59000, 59003) AS fixture_id;

INSERT INTO WS_WebService_Para (
	AD_Client_ID, AD_Org_ID, ConstantValue, Created, CreatedBy, IsActive,
	ParameterName, ParameterType, Updated, UpdatedBy, WS_WebService_Para_ID,
	WS_WebServiceType_ID)
SELECT
	11, 0, parameter.constant_value, now(), 100, 'Y',
	parameter.parameter_name, parameter.parameter_type, now(), 100,
	59000 + ((fixture.fixture_id - 59000) * 5) + parameter.ordinal,
	fixture.fixture_id
FROM generate_series(59000, 59003) AS fixture(fixture_id)
CROSS JOIN (
	VALUES
		(0, 'TableName', 'C', 'C_BPartner'),
		(1, 'RecordID', 'F', NULL),
		(2, 'Filter', 'F', NULL),
		(3, 'RetriveResultAs', 'C', 'Element'),
		(4, 'Action', 'F', NULL)
) AS parameter(ordinal, parameter_name, parameter_type, constant_value);

INSERT INTO WS_WebServiceFieldInput (
	AD_Client_ID, AD_Column_ID, AD_Org_ID, Created, CreatedBy, IsActive,
	Updated, UpdatedBy, WS_WebServiceFieldInput_ID, WS_WebServiceType_ID)
VALUES
	(11, 2901, 0, now(), 100, 'Y', now(), 100, 59000, 59001),
	(11, 2902, 0, now(), 100, 'Y', now(), 100, 59001, 59002),
	(11, 3081, 0, now(), 100, 'Y', now(), 100, 59002, 59002);

INSERT INTO WS_WebServiceFieldOutput (
	AD_Client_ID, AD_Column_ID, AD_Org_ID, Created, CreatedBy, IsActive,
	Updated, UpdatedBy, WS_WebServiceFieldOutput_ID, WS_WebServiceType_ID)
SELECT
	11, output_column.ad_column_id, 0, now(), 100, 'Y', now(), 100,
	59000 + ((fixture.fixture_id - 59000) * 6) + output_column.ordinal,
	fixture.fixture_id
FROM (VALUES (59000), (59001)) AS fixture(fixture_id)
CROSS JOIN (
	VALUES
		(0, 2893),
		(1, 2901),
		(2, 2902),
		(3, 2915),
		(4, 2916),
		(5, 3081)
) AS output_column(ordinal, ad_column_id);

DELETE FROM C_BPartner
WHERE C_BPartner_ID BETWEEN 1000001 AND 1000004;
INSERT INTO C_BPartner (
	C_BPartner_ID, AD_Client_ID, AD_Org_ID, CreatedBy, UpdatedBy,
	Value, Name, C_BP_Group_ID, IsVendor, IsCustomer)
VALUES
	(1000001, 11, 0, 100, 100, 'PHASE4-READ', 'Phase 4 Read Fixture', 103, 'N', 'Y'),
	(1000002, 11, 0, 100, 100, 'PHASE4-QUERY', 'Phase 4 Query Fixture', 103, 'N', 'Y'),
	(1000003, 11, 0, 100, 100, 'PHASE4-UPDATE', 'Phase 4 Update Before', 103, 'N', 'Y'),
	(1000004, 11, 0, 100, 100, 'PHASE4-DELETE', 'Phase 4 Delete Fixture', 103, 'N', 'Y');

COMMIT;
SQL

echo "Prepared deterministic Phase 4 valid-credential and CRUD scenarios"
