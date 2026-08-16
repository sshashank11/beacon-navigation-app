from enum import StrEnum


class Hazard(StrEnum):
    PM25 = "pm25"
    OZONE = "ozone"
    NO2 = "no2"
    POLLEN_TREE = "pollen_tree"
    POLLEN_GRASS = "pollen_grass"
    POLLEN_WEED = "pollen_weed"
    TRAFFIC_PROX = "traffic_prox"
    CONSTRUCTION = "construction"
    INDUSTRIAL_PROX = "industrial_prox"
    GRADE = "grade"
    HEAT = "heat"
    COLD_AIR = "cold_air"
    HUMIDITY = "humidity"
    CROWD_DENSITY = "crowd_density"
    SHADE_DEFICIT = "shade_deficit"
