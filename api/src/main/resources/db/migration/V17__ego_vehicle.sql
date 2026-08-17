-- Fraction of the lower frame occupied by the camera vehicle itself.
-- Dashcam frames shot through a windshield put the car's own dashboard across
-- the bottom of the image, which the model reads as "car" and which would
-- otherwise inflate the crowd prior on every corridor covered by a driving
-- sequence.
ALTER TABLE image_analysis
    ADD COLUMN ego_vehicle_frac REAL
        CHECK (ego_vehicle_frac IS NULL OR ego_vehicle_frac BETWEEN 0 AND 1);
