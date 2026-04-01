ALTER TABLE paragraphs
    ADD CONSTRAINT uk_paragraphs_node_index UNIQUE (node_id, index);
