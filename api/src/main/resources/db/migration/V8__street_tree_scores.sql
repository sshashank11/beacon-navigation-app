CREATE TABLE allergenic_tree_genus (
    genus TEXT PRIMARY KEY,
    common_group TEXT NOT NULL,
    allergenicity_rating REAL NOT NULL CHECK (allergenicity_rating BETWEEN 1 AND 3),
    pollen_season_start SMALLINT NOT NULL CHECK (pollen_season_start BETWEEN 1 AND 12),
    pollen_season_end SMALLINT NOT NULL CHECK (pollen_season_end BETWEEN 1 AND 12)
);

INSERT INTO allergenic_tree_genus
    (genus, common_group, allergenicity_rating, pollen_season_start, pollen_season_end)
VALUES
    ('Platanus', 'London plane', 3, 3, 5),
    ('Quercus', 'oak', 3, 3, 6),
    ('Betula', 'birch', 3, 3, 5),
    ('Morus', 'mulberry', 3, 3, 6),
    ('Fraxinus', 'ash', 2, 2, 5),
    ('Acer', 'maple', 2, 2, 5),
    ('Ulmus', 'elm', 2, 2, 4);

CREATE TABLE street_tree (
    tree_id BIGINT PRIMARY KEY,
    species_latin TEXT,
    species_common TEXT,
    genus TEXT,
    status TEXT NOT NULL,
    dbh_inches REAL CHECK (dbh_inches >= 0),
    geom GEOMETRY(Point, 4326) NOT NULL,
    geom_2263 GEOMETRY(Point, 2263)
        GENERATED ALWAYS AS (ST_Transform(geom, 2263)) STORED
);

CREATE INDEX street_tree_geom_gix ON street_tree USING GIST (geom);
CREATE INDEX street_tree_geom_2263_gix ON street_tree USING GIST (geom_2263);
CREATE INDEX street_tree_genus_idx ON street_tree (genus);

CREATE TABLE segment_tree_sample (
    segment_id BIGINT PRIMARY KEY REFERENCES segment(id) ON DELETE CASCADE,
    tree_count INTEGER NOT NULL CHECK (tree_count >= 0),
    allergen_weight REAL NOT NULL CHECK (allergen_weight >= 0),
    trees_per_100m REAL NOT NULL CHECK (trees_per_100m >= 0),
    pollen_weight_per_100m REAL NOT NULL CHECK (pollen_weight_per_100m >= 0),
    computed_at TIMESTAMPTZ NOT NULL
);
