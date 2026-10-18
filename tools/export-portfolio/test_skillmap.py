"""Unit tests for skillmap.py and the review-CSV writer. stdlib only.

Run with:  python3 -m unittest -v
"""

import csv
import io
import unittest

from export_portfolio import write_skill_csv
from skillmap import classify, normalize


class NormalizeTest(unittest.TestCase):
    def test_normalize_strips_collapses_and_casefolds(self):
        self.assertEqual(normalize("  Python\n\n   3  "), "python 3")


class ClassifyTest(unittest.TestCase):
    def test_exact_is_case_and_whitespace_insensitive(self):
        result = classify("  Python\n\n   3 ", ["Python 3", "Go"])
        self.assertEqual(result.status, "exact")
        self.assertEqual(result.proposed, ["Python 3"])
        self.assertEqual(result.candidates, [])

    def test_split_exact_when_every_part_is_a_label(self):
        result = classify("Python 3 / Pandas", ["Python 3", "Pandas", "R"])
        self.assertEqual(result.status, "split-exact")
        self.assertEqual(result.proposed, ["Python 3", "Pandas"])

    def test_split_with_only_one_matching_part_is_not_auto_proposed(self):
        result = classify("Python 3 / NumPy", ["Python 3", "R"])
        self.assertNotEqual(result.status, "split-exact")
        self.assertEqual(result.proposed, [])

    def test_fuzzy_whole_word_containment(self):
        result = classify("Apache Airflow", ["Airflow", "Python"])
        self.assertEqual(result.status, "fuzzy")
        self.assertEqual(result.proposed, [])
        self.assertEqual(result.candidates[0][0], "Airflow")

    def test_fuzzy_near_typo(self):
        result = classify("Pythno", ["Python", "Pandas"])
        self.assertEqual(result.status, "fuzzy")
        self.assertEqual(result.proposed, [])
        self.assertEqual(result.candidates[0][0], "Python")
        self.assertGreaterEqual(result.candidates[0][1], 0.80)

    def test_unmatched_unrelated_text(self):
        result = classify("MERGE REMOVE DUPLICATES", ["Python", "Airflow"])
        self.assertEqual(result.status, "unmatched")
        self.assertEqual(result.proposed, [])
        self.assertEqual(result.candidates, [])

    def test_determinism_same_input_gives_equal_output(self):
        text = "Apache Airflow / Spark"
        labels = ["Airflow", "Apache Spark", "Python"]
        self.assertEqual(classify(text, labels), classify(text, labels))

    def test_empty_and_none_like_inputs_do_not_raise(self):
        for value in ("", None, "   "):
            result = classify(value, ["Python"])
            self.assertEqual(result.status, "unmatched")
            self.assertEqual(result.proposed, [])


class ReviewCsvWriterTest(unittest.TestCase):
    def test_header_order_and_empty_decision(self):
        rows = [
            {
                "owner_type": "experience",
                "owner_slug": "x",
                "source_text": "Python 3 / Pandas",
                "status": "split-exact",
                "proposed_skills": ["Python 3", "Pandas"],
                "candidates": [],
                "decision": "",
            },
            {
                "owner_type": "project",
                "owner_slug": "y",
                "source_text": "Apache Airflow",
                "status": "fuzzy",
                "proposed_skills": [],
                "candidates": [("Airflow", 0.7619)],
                "decision": "",
            },
        ]
        buffer = io.StringIO()
        write_skill_csv(rows, buffer)
        buffer.seek(0)
        parsed = list(csv.reader(buffer))
        self.assertEqual(
            parsed[0],
            [
                "owner_type",
                "owner_slug",
                "source_text",
                "status",
                "proposed_skills",
                "candidates",
                "decision",
            ],
        )
        self.assertEqual(len(parsed), 3)
        first = dict(zip(parsed[0], parsed[1]))
        self.assertEqual(first["proposed_skills"], "Python 3; Pandas")
        second = dict(zip(parsed[0], parsed[2]))
        self.assertEqual(second["candidates"], "Airflow (0.76)")
        for row in parsed[1:]:
            self.assertEqual(row[-1], "")


if __name__ == "__main__":
    unittest.main()