"""Shared HTTP retry behaviour for provider calls.

Several providers rate limit per minute rather than per day, so a job that
walks a list of stations or images will trip a 429 partway through even with a
perfectly valid key. Retrying with backoff, and honouring ``Retry-After`` when
the provider sends it, is the difference between a job that finishes and a job
that dies halfway with a partial write.
"""

from __future__ import annotations

import time
from collections.abc import Callable, Mapping
from typing import Any

import httpx


RETRY_ATTEMPTS = 5
MAX_RETRY_DELAY_S = 90.0

# OpenAQ answers a 429 with x-ratelimit-reset seconds instead of Retry-After.
# Without reading it, exponential backoff gives up long before the window
# reopens and the job dies mid-walk.
RETRY_AFTER_HEADER = "Retry-After"
RATE_LIMIT_RESET_HEADER = "x-ratelimit-reset"
RATE_LIMIT_REMAINING_HEADER = "x-ratelimit-remaining"


def get_with_retry(
    client: httpx.Client,
    url: str,
    *,
    params: Mapping[str, Any] | None = None,
    headers: Mapping[str, str] | None = None,
    attempts: int = RETRY_ATTEMPTS,
    max_delay: float = MAX_RETRY_DELAY_S,
    sleep: Callable[[float], None] = time.sleep,
    cooldown: bool = True,
) -> httpx.Response:
    """GET that backs off on 429 and 5xx responses.

    Returns the final response without raising, so callers keep control of
    their own error handling. Client errors other than 429 return immediately;
    retrying a 401 or a 404 only wastes quota.

    With ``cooldown`` set, a response that reports an exhausted quota window
    pauses before returning. A job walking hundreds of stations would
    otherwise spend every retry budget it has re-discovering the same limit.
    """
    if attempts < 1:
        raise ValueError("attempts must be at least one")

    response: httpx.Response | None = None
    for attempt in range(attempts):
        final_attempt = attempt == attempts - 1
        try:
            response = client.get(url, params=params, headers=headers)
        except httpx.TransportError:
            # The connection dropped before any status arrived. A job pulling
            # thousands of thumbnails will meet this eventually, and it says
            # nothing about the next request, so retry rather than die.
            if final_attempt:
                raise
            sleep(min(float(2**attempt), max_delay))
            continue

        if response.status_code != 429 and response.status_code < 500:
            break
        if final_attempt:
            break
        sleep(min(retry_delay(response, attempt), max_delay))

    if response is None:
        raise RuntimeError("retry loop produced no response")

    if cooldown:
        pause = cooldown_seconds(response)
        if pause > 0:
            sleep(min(pause, max_delay))
    return response


def retry_delay(response: httpx.Response, attempt: int) -> float:
    """Seconds to wait, preferring whichever hint the provider actually sends."""
    for header in (RETRY_AFTER_HEADER, RATE_LIMIT_RESET_HEADER):
        raw = response.headers.get(header)
        if not raw:
            continue
        try:
            return max(float(raw), 0.0)
        except ValueError:
            continue
    return float(2**attempt)


def cooldown_seconds(response: httpx.Response) -> float:
    """Seconds to pause because the quota window is spent, else zero."""
    remaining = response.headers.get(RATE_LIMIT_REMAINING_HEADER)
    reset = response.headers.get(RATE_LIMIT_RESET_HEADER)
    if remaining is None or reset is None:
        return 0.0
    try:
        if float(remaining) > 0:
            return 0.0
        return max(float(reset), 0.0)
    except ValueError:
        return 0.0
