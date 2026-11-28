CREATE TABLE IF NOT EXISTS public_enquiries (
    id BIGSERIAL PRIMARY KEY,
    enquiry_no VARCHAR(64) NOT NULL UNIQUE,
    enquiry_type VARCHAR(32) NOT NULL DEFAULT 'CONTACT_RFQ',
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    full_name VARCHAR(180) NOT NULL,
    company_name VARCHAR(180),
    email VARCHAR(180),
    phone VARCHAR(60),
    city_state VARCHAR(180),
    product_series VARCHAR(200),
    nps_size VARCHAR(80),
    pressure_class VARCHAR(80),
    body_material VARCHAR(120),
    quantity_text VARCHAR(80),
    subject VARCHAR(255),
    message TEXT,
    rating SMALLINT,
    source_page VARCHAR(80),
    whatsapp_initiated BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_user_id INTEGER,
    converted_party_id INTEGER,
    converted_quotation_id INTEGER,
    internal_notes TEXT,
    client_ip VARCHAR(64),
    user_agent VARCHAR(255),
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_public_enquiries_status_created
    ON public_enquiries (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_public_enquiries_type
    ON public_enquiries (enquiry_type);

CREATE TABLE IF NOT EXISTS enquiry_activity (
    id BIGSERIAL PRIMARY KEY,
    enquiry_id BIGINT NOT NULL REFERENCES public_enquiries(id) ON DELETE CASCADE,
    action_type VARCHAR(48) NOT NULL,
    detail TEXT,
    actor_username VARCHAR(120),
    created_at VARCHAR(40) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_enquiry_activity_enquiry_id
    ON enquiry_activity (enquiry_id, id DESC);
