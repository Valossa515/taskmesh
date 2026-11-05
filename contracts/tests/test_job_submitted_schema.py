
import json
import unittest
from copy import deepcopy
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


CONTRACTS_DIR = Path(__file__).resolve().parents[1]


def load_json(relative_path):
    path = CONTRACTS_DIR / relative_path

    with path.open(encoding="utf-8") as file:
        return json.load(file)


class JobSubmittedSchemaTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        schema = load_json(
            "schemas/job-submitted.schema.json"
        )

        Draft202012Validator.check_schema(schema)

        cls.validator = Draft202012Validator(
            schema,
            format_checker=FormatChecker()
        )

        cls.valid_event = load_json(
            "examples/job-submitted.json"
        )

    def assert_invalid(self, event):
        self.assertFalse(
            self.validator.is_valid(event),
            "Expected event to be rejected"
        )

    def test_valid_event(self):
        self.assertTrue(
            self.validator.is_valid(self.valid_event)
        )

    def test_invalid_event_id(self):
        event = deepcopy(self.valid_event)
        event["eventId"] = "invalid-uuid"

        self.assert_invalid(event)

    def test_invalid_job_id(self):
        event = deepcopy(self.valid_event)
        event["data"]["jobId"] = "invalid-uuid"

        self.assert_invalid(event)

    def test_invalid_event_type(self):
        event = deepcopy(self.valid_event)
        event["eventType"] = "job.completed"

        self.assert_invalid(event)

    def test_invalid_schema_version(self):
        event = deepcopy(self.valid_event)
        event["schemaVersion"] = "2.0"

        self.assert_invalid(event)

    def test_invalid_timestamp(self):
        event = deepcopy(self.valid_event)
        event["occurredAt"] = "yesterday"

        self.assert_invalid(event)

    def test_missing_required_field(self):
        event = deepcopy(self.valid_event)
        del event["data"]["jobId"]

        self.assert_invalid(event)

    def test_payload_must_be_object(self):
        event = deepcopy(self.valid_event)
        event["data"]["payload"] = "invalid"

        self.assert_invalid(event)

    def test_unknown_field_is_rejected(self):
        event = deepcopy(self.valid_event)
        event["data"]["priority"] = 5

        self.assert_invalid(event)


if __name__ == "__main__":
    unittest.main()
