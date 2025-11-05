-- enable full replication, otherwise
ALTER TABLE edc_contract_negotiation REPLICA IDENTITY FULL;
ALTER TABLE edc_transfer_process REPLICA IDENTITY FULL;