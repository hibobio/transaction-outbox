---
name: generate-employee-import
description: Generates .xlsx files with dummy employee data for HiBob import. Use when the user asks to generate employee import files, create test employees, or produce dummy employee data for import. Requires companyId and employee count.
---

# Generate Employee Import XLSX

Generates one or more `.xlsx` files with realistic dummy employee data ready for HiBob import.

## Input requirements

- **`companyId`** (required): If not provided by the user, ask: "What is the companyId for the import?"
- **`count`** (required): Number of employees to generate. If not provided, ask: "How many employees should I generate?"
  - If count > 1000, multiple files are created automatically (max 1000 rows each)

## Output columns

| Column | Format | Example |
|--------|--------|---------|
| `email` | `firstname.lastname+{companyId}@hibob.io` (lowercase) | `jane.smith+655182@hibob.io` |
| `First Name` | — | `Jane` |
| `Last Name` | — | `Smith` |
| `Start Date` | `DD/MM/YYYY` by default; `MM/DD/YYYY` available via flag | `03/01/2023` |
| `Site` | Fixed list of 5 demo sites | `Tel Aviv (Demo)` |
| `Job Title` | Randomly picked from predefined list | `Product Manager` |

**Fixed sites (always these 5):**
- Hong Kong (Demo)
- London (Demo)
- New York (Demo)
- Tel Aviv (Demo)
- Toronto (Demo)

**Job title options:**
Account Manager, Business Development Manager, Chief Architect, Chief Engineer, Content Marketing Manager, Content Writer, Designer, Developer, Senior Developer, Junior Developer, Head Of Client Services, HR Manager, HR Administrator, Intern, Marketing Manager, Product Manager, Sales Manager, Social Media Manager, Office Manager, Team Leader, UX, VP Business Development, VP Finance, VP Marketing, VP Operations, VP Product, VP R&D, VP Sales, Web Designer

**Guarantees:**
- All `(First Name, Last Name)` pairs are unique across all generated files

## How to run

First, ensure dependencies are installed:
```bash
pip install faker openpyxl
```

Then run:
```bash
python3 .cursor/skills/generate-employee-import/scripts/generate_employees.py <companyId> <count> [--date-format=DD/MM/YYYY|MM/DD/YYYY]
```

### Examples

```bash
# 50 employees, default date format (DD/MM/YYYY) → employee-import.xlsx
python3 .cursor/skills/generate-employee-import/scripts/generate_employees.py 655182 50

# 50 employees with MM/DD/YYYY dates
python3 .cursor/skills/generate-employee-import/scripts/generate_employees.py 655182 50 --date-format=MM/DD/YYYY

# 2500 employees → employee-import-1.xlsx, employee-import-2.xlsx, employee-import-3.xlsx
python3 .cursor/skills/generate-employee-import/scripts/generate_employees.py 655182 2500
```

Files are written to the **current working directory**.

## After running

Report to the user:
- Each filename and the number of employees it contains
