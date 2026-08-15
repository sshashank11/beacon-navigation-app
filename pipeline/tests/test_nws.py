from __future__ import annotations

import unittest

from beacon_pipeline.ingest.nws import _parse_alerts, _period_to_readings


class NwsIngestTest(unittest.TestCase):
    def test_period_extracts_weather_and_wind(self) -> None:
        rows = _period_to_readings(
            {
                "startTime": "2026-08-15T16:00:00-04:00",
                "temperature": 86,
                "temperatureUnit": "F",
                "relativeHumidity": {"value": 68},
                "windSpeed": "10 to 15 mph",
                "windDirection": "SW",
            }
        )

        values = {row.hazard: row.value for row in rows}
        self.assertAlmostEqual(values["heat"], 30.0)
        self.assertEqual(values["humidity"], 68.0)
        self.assertEqual(values["wind_speed"], 10.0)
        self.assertEqual(values["wind_bearing"], 225.0)

    def test_alert_parser_keeps_active_alert_details(self) -> None:
        rows = _parse_alerts(
            {
                "features": [
                    {
                        "id": "alert-1",
                        "properties": {
                            "event": "Air Quality Alert",
                            "headline": "Air Quality Alert issued August 15",
                            "severity": "Moderate",
                            "urgency": "Expected",
                            "sent": "2026-08-15T12:00:00Z",
                            "expires": "2026-08-16T03:00:00Z",
                        },
                    }
                ]
            }
        )

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0].event, "Air Quality Alert")
        self.assertEqual(rows[0].expires_at.isoformat(), "2026-08-16T03:00:00+00:00")


if __name__ == "__main__":
    unittest.main()
