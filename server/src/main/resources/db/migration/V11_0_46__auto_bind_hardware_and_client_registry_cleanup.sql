-- ============================================================================
-- DSE ERP 11.0.46: Auto-Bind Hardware, Client Registry Cleanup & Template Engine
-- ============================================================================

-- 1. Remove hardcoded dummy/test machine hash from app_license so local machine can self-activate cleanly
UPDATE app_license
SET
    machine_hash = 'AUTO_BIND_HOST',
    status = 'ACTIVE',
    last_verified_at = CURRENT_TIMESTAMP
WHERE machine_hash = 'F12DC2C0AADE15EB385BC67EE919B9CD663B49D0482D2C33EA3014B92245B6ED'
   OR machine_hash = 'F12D-C2C0-AADE-15EB';

-- 2. Clean up licensed_client_registry seeded machine_id
UPDATE licensed_client_registry
SET
    machine_id = 'AUTO_BIND_HOST',
    notes = 'Dynamic local host auto-binding authorized'
WHERE machine_id = 'F12D-C2C0-AADE-15EB';

-- 3. Clean up client_profiles empty or dot registered address for Jasvi Industries
UPDATE client_profiles
SET
    address = 'GF, SHED NO 6, HARI KRISHNA ESTATE, KATHWADA SINGARVA ROAD, KATHWADA NIKOL, OPP KRISHNA IND ESTATE, Ahmedabad, Gujarat, 382350',
    trade_name = 'JASVI INDUSTRIES',
    legal_name = 'BIPIN SHAMBHUBHAI DHAMELIYA'
WHERE gstin = '24ANXPD3352N1ZK'
   OR client_name ILIKE '%JASVI%'
   OR address = '.'
   OR address IS NULL;
