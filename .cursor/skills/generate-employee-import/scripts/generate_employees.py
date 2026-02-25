#!/usr/bin/env python3
"""Generate dummy employee XLSX files for HiBob import."""

import math
import random
import sys

try:
    from faker import Faker
except ImportError:
    print("Missing dependency: run 'pip install faker openpyxl'")
    sys.exit(1)

try:
    from openpyxl import Workbook
except ImportError:
    print("Missing dependency: run 'pip install faker openpyxl'")
    sys.exit(1)


HEADERS = ["email", "First Name", "Last Name", "Start Date", "Site", "Job Title"]
MAX_PER_FILE = 1000

SITES = [
    "Hong Kong (Demo)",
    "London (Demo)",
    "New York (Demo)",
    "Tel Aviv (Demo)",
    "Toronto (Demo)",
]

JOB_TITLES = [
    "Account Manager",
    "Business Development Manager",
    "Chief Architect",
    "Chief Engineer",
    "Content Marketing Manager",
    "Content Writer",
    "Designer",
    "Developer",
    "Senior Developer",
    "Junior Developer",
    "Head Of Client Services",
    "HR Manager",
    "HR Administrator",
    "Intern",
    "Marketing Manager",
    "Product Manager",
    "Sales Manager",
    "Social Media Manager",
    "Office Manager",
    "Team Leader",
    "UX",
    "VP Business Development",
    "VP Finance",
    "VP Marketing",
    "VP Operations",
    "VP Product",
    "VP R&D",
    "VP Sales",
    "Web Designer",
]


DATE_FORMATS = {
    "DD/MM/YYYY": "%d/%m/%Y",
    "MM/DD/YYYY": "%m/%d/%Y",
}
DEFAULT_DATE_FORMAT = "DD/MM/YYYY"


def generate_all_employees(fake: Faker, company_id: str, count: int, date_fmt: str) -> list[list]:
    seen: set[tuple[str, str]] = set()
    employees = []

    while len(employees) < count:
        first = fake.first_name()
        last = fake.last_name()
        key = (first.lower(), last.lower())
        if key in seen:
            continue
        seen.add(key)

        email = f"{key[0]}.{key[1]}+{company_id}@hibob.io"
        start_date = fake.date_between(start_date="-5y", end_date="today").strftime(date_fmt)
        site = random.choice(SITES)
        job_title = random.choice(JOB_TITLES)

        employees.append([email, first, last, start_date, site, job_title])

    return employees


def write_files(employees: list[list], total: int) -> list[tuple[str, int]]:
    num_files = math.ceil(total / MAX_PER_FILE)
    written = []

    for idx in range(num_files):
        chunk = employees[idx * MAX_PER_FILE : (idx + 1) * MAX_PER_FILE]
        filename = f"employee-import-{idx + 1}.xlsx" if num_files > 1 else "employee-import.xlsx"

        wb = Workbook()
        ws = wb.active
        ws.append(HEADERS)
        for row in chunk:
            ws.append(row)

        wb.save(filename)
        written.append((filename, len(chunk)))

    return written


def parse_date_format(args: list[str]) -> str:
    for arg in args:
        if arg.startswith("--date-format="):
            key = arg.split("=", 1)[1].upper()
            if key not in DATE_FORMATS:
                print(f"Unknown date format '{key}'. Options: {', '.join(DATE_FORMATS)}")
                sys.exit(1)
            return DATE_FORMATS[key]
    return DATE_FORMATS[DEFAULT_DATE_FORMAT]


def main() -> None:
    if len(sys.argv) < 3:
        print("Usage: python3 generate_employees.py <companyId> <count> [--date-format=DD/MM/YYYY|MM/DD/YYYY]")
        sys.exit(1)

    company_id = sys.argv[1]
    count = int(sys.argv[2])
    date_fmt = parse_date_format(sys.argv[3:])

    if count < 1:
        print("Count must be at least 1.")
        sys.exit(1)

    fake = Faker()
    print(f"Sites ({len(SITES)}): {', '.join(SITES)}")

    employees = generate_all_employees(fake, company_id, count, date_fmt)
    written = write_files(employees, count)

    for filename, rows in written:
        print(f"Generated: {filename} ({rows} employees)")


if __name__ == "__main__":
    main()
