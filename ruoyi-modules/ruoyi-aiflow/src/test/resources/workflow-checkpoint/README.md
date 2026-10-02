# Synthetic legacy checkpoint fixture

Generated on 2026-10-02 using the official LangGraph4j 1.8.20 CheckpointSerializer and ObjectStreamStateSerializer in an isolated temporary directory. Contains synthetic data only; no database export, credentials or business documents. Binary SHA-256: `065a5324b231f1c169887a5174c524afd4ac469b58a6b1eea02bf3ffbaf0ff95`.

The production dependency is removed. This immutable fixture independently verifies the read-only legacy protocol decoder, including 132000 UTF-8 bytes of Chinese text, null values, nested List/Map/Set and every NodeIOData content subtype. It does not prove recovery of real historical runs; the checked local database had zero checkpoint rows.
