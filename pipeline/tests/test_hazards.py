import unittest

from beacon_pipeline.hazards import Hazard


class HazardTest(unittest.TestCase):
    def test_canonical_vector_is_stable(self) -> None:
        self.assertEqual(
            [hazard.value for hazard in Hazard],
            [
                "pm25",
                "ozone",
                "no2",
                "pollen_tree",
                "pollen_grass",
                "pollen_weed",
                "traffic_prox",
                "construction",
                "industrial_prox",
                "grade",
                "heat",
                "cold_air",
                "humidity",
                "crowd_density",
                "shade_deficit",
            ],
        )


if __name__ == "__main__":
    unittest.main()
