from __future__ import annotations

import unittest

import httpx

from beacon_pipeline.http import cooldown_seconds, get_with_retry, retry_delay


class GetWithRetryTest(unittest.TestCase):
    def _client(self, statuses: list[int], headers: dict[str, str] | None = None):
        calls: list[int] = []

        def handle(request: httpx.Request) -> httpx.Response:
            status = statuses[min(len(calls), len(statuses) - 1)]
            calls.append(status)
            return httpx.Response(status, headers=headers or {}, text="body")

        return httpx.Client(transport=httpx.MockTransport(handle)), calls

    def test_retries_a_rate_limit_until_it_succeeds(self) -> None:
        client, calls = self._client([429, 429, 200])
        slept: list[float] = []

        with client:
            response = get_with_retry(
                client, "https://api.example/x", sleep=slept.append
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(calls), 3)
        self.assertEqual(slept, [1.0, 2.0])

    def test_retries_server_errors(self) -> None:
        client, calls = self._client([503, 200])
        with client:
            response = get_with_retry(
                client, "https://api.example/x", sleep=lambda _: None
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(calls), 2)

    def test_does_not_retry_an_auth_failure(self) -> None:
        client, calls = self._client([401])
        with client:
            response = get_with_retry(
                client, "https://api.example/x", sleep=lambda _: None
            )

        self.assertEqual(response.status_code, 401)
        self.assertEqual(len(calls), 1, "retrying a bad key only wastes quota")

    def test_gives_up_and_returns_the_last_response(self) -> None:
        client, calls = self._client([429])
        with client:
            response = get_with_retry(
                client, "https://api.example/x", attempts=3, sleep=lambda _: None
            )

        self.assertEqual(response.status_code, 429)
        self.assertEqual(len(calls), 3)

    def test_honours_retry_after_and_the_delay_cap(self) -> None:
        client, _ = self._client([429, 200], headers={"Retry-After": "120"})
        slept: list[float] = []

        with client:
            get_with_retry(
                client, "https://api.example/x", max_delay=30.0, sleep=slept.append
            )

        self.assertEqual(slept, [30.0])

    def test_retries_a_dropped_connection(self) -> None:
        """A multi-hour harvest will meet a mid-flight disconnect."""
        calls: list[int] = []

        def handle(request: httpx.Request) -> httpx.Response:
            calls.append(1)
            if len(calls) < 3:
                raise httpx.RemoteProtocolError(
                    "Server disconnected without sending a response.",
                    request=request,
                )
            return httpx.Response(200)

        slept: list[float] = []
        with httpx.Client(transport=httpx.MockTransport(handle)) as client:
            response = get_with_retry(
                client, "https://api.example/x", sleep=slept.append
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(calls), 3)
        self.assertEqual(slept, [1.0, 2.0])

    def test_a_persistent_connection_failure_still_raises(self) -> None:
        def handle(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("no route to host", request=request)

        with httpx.Client(transport=httpx.MockTransport(handle)) as client:
            with self.assertRaises(httpx.ConnectError):
                get_with_retry(
                    client, "https://api.example/x", attempts=2, sleep=lambda _: None
                )

    def test_a_timeout_is_retried_like_any_transport_error(self) -> None:
        calls: list[int] = []

        def handle(request: httpx.Request) -> httpx.Response:
            calls.append(1)
            if len(calls) == 1:
                raise httpx.ReadTimeout("timed out", request=request)
            return httpx.Response(200)

        with httpx.Client(transport=httpx.MockTransport(handle)) as client:
            response = get_with_retry(
                client, "https://api.example/x", sleep=lambda _: None
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(calls), 2)

    def test_rejects_a_non_positive_attempt_count(self) -> None:
        client, _ = self._client([200])
        with client, self.assertRaisesRegex(ValueError, "at least one"):
            get_with_retry(client, "https://api.example/x", attempts=0)


class RetryDelayTest(unittest.TestCase):
    def test_prefers_retry_after_then_falls_back_to_backoff(self) -> None:
        with_hint = httpx.Response(429, headers={"Retry-After": "7"})
        without = httpx.Response(429)
        garbage = httpx.Response(429, headers={"Retry-After": "soon"})

        self.assertEqual(retry_delay(with_hint, 0), 7.0)
        self.assertEqual(retry_delay(without, 3), 8.0)
        self.assertEqual(retry_delay(garbage, 2), 4.0)

    def test_reads_the_openaq_reset_header(self) -> None:
        # OpenAQ sends no Retry-After, only x-ratelimit-reset in seconds.
        response = httpx.Response(429, headers={"x-ratelimit-reset": "21"})

        self.assertEqual(retry_delay(response, 0), 21.0)

    def test_retry_after_wins_when_both_are_present(self) -> None:
        response = httpx.Response(
            429, headers={"Retry-After": "5", "x-ratelimit-reset": "60"}
        )

        self.assertEqual(retry_delay(response, 0), 5.0)


class CooldownTest(unittest.TestCase):
    def test_pauses_only_when_the_quota_window_is_spent(self) -> None:
        spent = httpx.Response(
            200, headers={"x-ratelimit-remaining": "0", "x-ratelimit-reset": "21"}
        )
        available = httpx.Response(
            200, headers={"x-ratelimit-remaining": "42", "x-ratelimit-reset": "21"}
        )
        silent = httpx.Response(200)

        self.assertEqual(cooldown_seconds(spent), 21.0)
        self.assertEqual(cooldown_seconds(available), 0.0)
        self.assertEqual(cooldown_seconds(silent), 0.0)

    def test_a_spent_quota_pauses_before_returning(self) -> None:
        def handle(request: httpx.Request) -> httpx.Response:
            return httpx.Response(
                200,
                headers={"x-ratelimit-remaining": "0", "x-ratelimit-reset": "21"},
            )

        client = httpx.Client(transport=httpx.MockTransport(handle))
        slept: list[float] = []

        with client:
            response = get_with_retry(
                client, "https://api.example/x", sleep=slept.append
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(slept, [21.0])


if __name__ == "__main__":
    unittest.main()
