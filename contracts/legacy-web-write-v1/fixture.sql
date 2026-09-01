-- Phase 5g-1a reviewed write fixture.
--
-- This file deliberately creates almost nothing. The Business Partner flow is
-- driven entirely through the web UI, and pre-creating its rows here would
-- score the fixture instead of the window -- the oracle would then prove that
-- SQL can insert a row, which was never in doubt.
--
-- What it does instead is ASSERT the preconditions the flow depends on but does
-- not itself establish. Every assertion here is a condition that, if violated,
-- produces a confusing browser-level failure a long way from its cause: a
-- leaked business partner from a previous capture surfaces as a duplicate-key
-- error inside a ZK AU response, and a missing default business partner group
-- surfaces as an empty mandatory combo. Failing here, in SQL, with a named
-- reason, is worth considerably more than failing there.
--
-- Applied by scripts/phase5/reset-write-oracle-fixture.sh after every reseed.

\set ON_ERROR_STOP on

DO $fixture$
DECLARE
    leaked integer;
    role_id integer;
BEGIN
    -- 1. The fixture key must be absent.
    --
    -- The reseed restores a golden archive taken from the installed product, so
    -- this row cannot legitimately exist. If it does, the reseed did not happen
    -- or did not complete, and capture B would start from capture A's state --
    -- precisely the drift full restore exists to prevent.
    SELECT count(*) INTO leaked
    FROM C_BPartner
    WHERE Value LIKE 'P5G1A-%';
    IF leaked <> 0 THEN
        RAISE EXCEPTION
            'Phase 5g-1a fixture precondition failed: % business partner row(s) with a P5G1A- key survived the reseed. The database was not restored from the golden archive.',
            leaked;
    END IF;

    -- 2. The two capture identities must exist and be active.
    --
    -- GardenAdmin drives the create/update/deactivate flow. GardenUser is the
    -- second editor in the concurrency step, and it must be a DIFFERENT user so
    -- the captured conflict records a real UpdatedBy transition rather than one
    -- user racing itself.
    IF NOT EXISTS (
        SELECT 1 FROM AD_User
        WHERE AD_User_ID = 101 AND Name = 'GardenAdmin' AND IsActive = 'Y'
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: AD_User 101 (GardenAdmin) is missing or inactive.';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM AD_User
        WHERE AD_User_ID = 102 AND Name = 'GardenUser' AND IsActive = 'Y'
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: AD_User 102 (GardenUser) is missing or inactive.';
    END IF;

    -- 3. Both identities must be able to WRITE the Business Partner window.
    --
    -- Asserted rather than assumed. If either role were read-only on window 123
    -- the concurrency step would capture an access-control refusal and freeze it
    -- as the "conflict behaviour", which would be wrong and would not be
    -- obviously wrong.
    IF NOT EXISTS (
        SELECT 1 FROM AD_Window_Access
        WHERE AD_Window_ID = 123 AND AD_Role_ID = 102
          AND IsReadWrite = 'Y' AND IsActive = 'Y'
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: role 102 lacks read-write access to window 123.';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM AD_Window_Access
        WHERE AD_Window_ID = 123 AND AD_Role_ID = 103
          AND IsReadWrite = 'Y' AND IsActive = 'Y'
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: role 103 lacks read-write access to window 123.';
    END IF;

    -- 4. The Business Partner tab must still be insertable and writable.
    --
    -- AD_Tab 220 carries IsInsertRecord='Y' and IsReadOnly='N' in the seed. The
    -- create step depends on both. A dictionary change that flipped either would
    -- otherwise turn into an unexplained missing New Record control.
    IF NOT EXISTS (
        SELECT 1 FROM AD_Tab
        WHERE AD_Tab_ID = 220 AND AD_Window_ID = 123 AND AD_Table_ID = 291
          AND IsInsertRecord = 'Y' AND IsReadOnly = 'N' AND IsActive = 'Y'
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: AD_Tab 220 is no longer an insertable, writable C_BPartner tab.';
    END IF;

    -- 5. A default business partner group must exist for the client.
    --
    -- C_BP_Group is mandatory on C_BPartner. The seed marks group 103
    -- ("Standard Customers") as the GardenWorld default, and the create step
    -- relies on that default being supplied rather than typed.
    IF NOT EXISTS (
        SELECT 1 FROM C_BP_Group
        WHERE C_BP_Group_ID = 103 AND AD_Client_ID = 11
          AND IsDefault = 'Y' AND IsActive = 'Y'
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: GardenWorld has no default C_BP_Group 103.';
    END IF;

    -- 6. The attribution claim must still hold at the dictionary level.
    --
    -- contracts/legacy-web-write-v1/attribution.tsv claims C_BPartner fires no
    -- callout. That claim is gated statically against the seed XML, but the
    -- capture runs against a RESTORED DATABASE, and a migration applied during
    -- install could in principle add one. Asserting it here closes the gap
    -- between the file the gate reads and the database the capture measures.
    IF EXISTS (
        SELECT 1 FROM AD_Column
        WHERE AD_Table_ID = 291 AND IsActive = 'Y'
          AND Callout IS NOT NULL AND btrim(Callout) <> ''
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: C_BPartner now declares a callout in the RUNTIME database, so the write effect is no longer attributable to the window and model layer alone.';
    END IF;

    -- 7. The capture organisation must be writable by BOTH identities.
    --
    -- This is the constraint assertion 3 was written to rule out and did not.
    -- AD_Window_Access governs whether a role may open the window; it says
    -- nothing about whether a role may write a record belonging to a given
    -- organisation. Run 33482988481 satisfied every window-access assertion
    -- above and still served the second editor a read-only form, because the
    -- record had been created in the shared '*' organisation while GardenUser
    -- was logged into Fertilizer. The concurrency step would then have captured
    -- ADempiere's org access refusal and frozen it as the product's conflict
    -- behaviour -- wrong, and not obviously wrong.
    --
    -- The driver now creates the record in the organisation named here, so the
    -- name and both roles' access to it are preconditions of the capture.
    IF NOT EXISTS (
        SELECT 1 FROM AD_Org
        WHERE Name = 'Fertilizer' AND AD_Client_ID = 11 AND IsActive = 'Y'
    ) THEN
        RAISE EXCEPTION 'Phase 5g-1a fixture precondition failed: GardenWorld has no active organisation named Fertilizer to create the captured record in.';
    END IF;
    FOR role_id IN SELECT unnest(ARRAY[102, 103]) LOOP
        IF NOT EXISTS (
            SELECT 1 FROM AD_Role r
            WHERE r.AD_Role_ID = role_id
              AND (r.IsAccessAllOrgs = 'Y'
                   OR EXISTS (
                       SELECT 1 FROM AD_Role_OrgAccess oa
                       JOIN AD_Org o ON o.AD_Org_ID = oa.AD_Org_ID
                       WHERE oa.AD_Role_ID = r.AD_Role_ID
                         AND oa.IsActive = 'Y' AND o.IsActive = 'Y'
                         AND o.Name = 'Fertilizer' AND o.AD_Client_ID = 11))
        ) THEN
            RAISE EXCEPTION
                'Phase 5g-1a fixture precondition failed: role % cannot write the Fertilizer organisation, so the captured record would be read-only for one of the two editors.',
                role_id;
        END IF;
    END LOOP;

    RAISE NOTICE 'Phase 5g-1a fixture preconditions satisfied.';
END
$fixture$;
