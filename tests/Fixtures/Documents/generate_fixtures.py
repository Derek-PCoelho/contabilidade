"""Generate deterministic, synthetic Phase 4 PDF golden fixtures."""

from __future__ import annotations

import json
from pathlib import Path

from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen.canvas import Canvas


ROOT = Path(__file__).resolve().parent
PDF_DIR = ROOT / "Pdf"
TEXT_DIR = ROOT / "Text"
EXPECTED_DIR = ROOT / "Expected"
NOTICE = "DADOS SINTETICOS - SEM VALIDADE"


CASES = {
    "ferias": {
        "type": "Vacation",
        "pages": [[
            "RECIBO DE FERIAS",
            "EMPREGADOR: MICHELLY SERVICOS CONTABEIS LTDA",
            "EMPREGADOR CNPJ: 11.222.333/0001-81",
            "EMPREGADO: ANA SILVA SINTETICA",
            "EMPREGADO CPF: 529.982.247-25",
            "PERIODO DE GOZO: 01/09/2026 a 30/09/2026",
            "VALOR LIQUIDO: R$ 3.245,67",
        ]],
        "fields": [
            ["EmpregadorCnpj", "11222333000181", "EmployerTaxId", 1],
            ["Empregador", "MICHELLY SERVICOS CONTABEIS LTDA", "EmployerName", 1],
            ["EmpregadoCpf", "52998224725", "EmployeeCpf", 1],
            ["PeriodoDeGozo", "01/09/2026 a 30/09/2026", "VacationPeriod", 1],
            ["ValorLiquido", "3245.67", "TotalAmount", 1],
        ],
    },
    "fgts_digital": {
        "type": "FgtsDigital",
        "pages": [[
            "GUIA DO FGTS DIGITAL",
            "FGTS DIGITAL",
            "RAZAO SOCIAL: MICHELLY SERVICOS CONTABEIS LTDA",
            "EMPREGADOR CNPJ: 11.222.333/0001-81",
            "COMPETENCIA: 08/2026",
            "VENCIMENTO: 07/09/2026",
            "VALOR TOTAL: R$ 8.765,43",
        ]],
        "fields": [
            ["EmpregadorCnpj", "11222333000181", "EmployerTaxId", 1],
            ["RazaoSocial", "MICHELLY SERVICOS CONTABEIS LTDA", "EmployerName", 1],
            ["Competencia", "08/2026", "Competence", 1],
            ["Vencimento", "2026-09-07", "DueDate", 1],
            ["ValorTotal", "8765.43", "TotalAmount", 1],
        ],
    },
    "folha_pagamento": {
        "type": "Payroll",
        "pages": [
            [
                "FOLHA DE PAGAMENTO",
                "EMPREGADOR: MICHELLY SERVICOS CONTABEIS LTDA",
                "EMPREGADOR CNPJ: 11.222.333/0001-81",
                "COMPETENCIA: 08/2026",
                "PAGINA DE FUNCIONARIOS: 1",
            ],
            [
                "FOLHA DE PAGAMENTO",
                "EMPREGADOR: MICHELLY SERVICOS CONTABEIS LTDA",
                "EMPREGADOR CNPJ: 11.222.333/0001-81",
                "COMPETENCIA: 08/2026",
                "PAGINA DE FUNCIONARIOS: 2",
            ],
            [
                "FOLHA DE PAGAMENTO",
                "EMPREGADOR: MICHELLY SERVICOS CONTABEIS LTDA",
                "EMPREGADOR CNPJ: 11.222.333/0001-81",
                "COMPETENCIA: 08/2026",
                "TOTAL DA FOLHA: R$ 42.345,90",
            ],
        ],
        "fields": [
            ["EmpregadorCnpj", "11222333000181", "EmployerTaxId", 1],
            ["Empregador", "MICHELLY SERVICOS CONTABEIS LTDA", "EmployerName", 1],
            ["Competencia", "08/2026", "Competence", 1],
            ["TotalDaFolha", "42345.90", "TotalAmount", 3],
        ],
    },
    "darf": {
        "type": "FederalRevenueCollection",
        "pages": [[
            "DOCUMENTO DE ARRECADACAO DE RECEITAS FEDERAIS",
            "DARF",
            "RAZAO SOCIAL: MICHELLY SERVICOS CONTABEIS LTDA",
            "CONTRIBUINTE CNPJ: 11.222.333/0001-81",
            "PERIODO DE APURACAO: 31/08/2026",
            "VENCIMENTO: 18/09/2026",
            "VALOR TOTAL: R$ 5.678,12",
        ]],
        "fields": [
            ["ContribuinteCnpj", "11222333000181", "ClientTaxId", 1],
            ["RazaoSocial", "MICHELLY SERVICOS CONTABEIS LTDA", "ClientName", 1],
            ["PeriodoApuracao", "2026-08-31", "AssessmentPeriod", 1],
            ["Vencimento", "2026-09-18", "DueDate", 1],
            ["ValorTotal", "5678.12", "TotalAmount", 1],
        ],
    },
    "decimo_terceiro": {
        "type": "ThirteenthSalary",
        "pages": [[
            "DECIMO TERCEIRO SALARIO",
            "EMPREGADOR: MICHELLY SERVICOS CONTABEIS LTDA - FILIAL",
            "EMPREGADOR CNPJ: 11.222.333/0002-62",
            "EMPREGADO: BRUNO LIMA SINTETICO",
            "EMPREGADO CPF: 529.982.247-25",
            "COMPETENCIA: 12/2026",
            "VALOR LIQUIDO: R$ 2.789,01",
        ]],
        "fields": [
            ["EmpregadorCnpj", "11222333000262", "EmployerTaxId", 1],
            ["Empregador", "MICHELLY SERVICOS CONTABEIS LTDA - FILIAL", "EmployerName", 1],
            ["EmpregadoCpf", "52998224725", "EmployeeCpf", 1],
            ["Competencia", "12/2026", "Competence", 1],
            ["ValorLiquido", "2789.01", "TotalAmount", 1],
        ],
    },
    "pro_labore": {
        "type": "ProLabore",
        "pages": [[
            "RECIBO DE PRO-LABORE",
            "PRO-LABORE",
            "EMPRESA: MICHELLY SERVICOS CONTABEIS LTDA",
            "EMPRESA CNPJ: 11.222.333/0001-81",
            "SOCIO: CARLA SOUZA SINTETICA",
            "SOCIO CPF: 111.444.777-35",
            "COMPETENCIA: 08/2026",
            "VALOR LIQUIDO: R$ 4.500,00",
        ]],
        "fields": [
            ["EmpresaCnpj", "11222333000181", "EmployerTaxId", 1],
            ["Empresa", "MICHELLY SERVICOS CONTABEIS LTDA", "EmployerName", 1],
            ["SocioCpf", "11144477735", "PartnerCpf", 1],
            ["Competencia", "08/2026", "Competence", 1],
            ["ValorLiquido", "4500.00", "TotalAmount", 1],
        ],
    },
    "rescisao_sindicato_primeiro": {
        "type": "Termination",
        "pages": [[
            "TERMO DE RESCISAO DO CONTRATO DE TRABALHO",
            "SINDICATO: SINDICATO SINTETICO DOS TRABALHADORES",
            "SINDICATO CNPJ: 04.252.011/0001-10",
            "EMPREGADOR: MICHELLY SERVICOS CONTABEIS LTDA",
            "EMPREGADOR CNPJ: 11.222.333/0001-81",
            "TRABALHADOR: DIEGO COSTA SINTETICO",
            "TRABALHADOR CPF: 529.982.247-25",
            "DATA DE DESLIGAMENTO: 20/08/2026",
            "VALOR LIQUIDO: R$ 7.654,32",
        ]],
        "fields": [
            ["SindicatoCnpj", "04252011000110", "UnionTaxId", 1],
            ["EmpregadorCnpj", "11222333000181", "EmployerTaxId", 1],
            ["Empregador", "MICHELLY SERVICOS CONTABEIS LTDA", "EmployerName", 1],
            ["TrabalhadorCpf", "52998224725", "EmployeeCpf", 1],
            ["DataDesligamento", "2026-08-20", "EventDate", 1],
            ["ValorLiquido", "7654.32", "TotalAmount", 1],
        ],
    },
}


def draw_page(canvas: Canvas, lines: list[str], page_number: int, page_count: int) -> None:
    width, height = A4
    canvas.setFillColor(HexColor("#A12622"))
    canvas.setFont("Helvetica-Bold", 11)
    canvas.drawCentredString(width / 2, height - 34, NOTICE)
    canvas.setStrokeColor(HexColor("#D8C58D"))
    canvas.line(52, height - 48, width - 52, height - 48)

    y = height - 92
    for index, line in enumerate(lines):
        if index == 0:
            canvas.setFillColor(HexColor("#17202A"))
            canvas.setFont("Helvetica-Bold", 17)
        else:
            canvas.setFillColor(HexColor("#263238"))
            canvas.setFont("Helvetica", 11)
        canvas.drawString(58, y, line)
        y -= 34 if index == 0 else 25

    canvas.setFillColor(HexColor("#6B7280"))
    canvas.setFont("Helvetica", 8)
    canvas.drawString(58, 36, f"Fixture deterministica da Fase 4 - pagina {page_number}/{page_count}")
    canvas.drawRightString(width - 58, 36, NOTICE)
    canvas.showPage()


def generate() -> None:
    for directory in (PDF_DIR, TEXT_DIR, EXPECTED_DIR):
        directory.mkdir(parents=True, exist_ok=True)

    for case_name, case in CASES.items():
        pdf_path = PDF_DIR / f"{case_name}.pdf"
        canvas = Canvas(
            str(pdf_path),
            pagesize=A4,
            pageCompression=1,
            invariant=1,
        )
        canvas.setAuthor("Folhas da Michelly - dados sinteticos")
        canvas.setCreator("Phase 4 deterministic fixture generator")
        canvas.setSubject(NOTICE)
        canvas.setTitle(f"Fixture sintetica - {case_name}")
        pages = case["pages"]
        for page_number, lines in enumerate(pages, start=1):
            draw_page(canvas, lines, page_number, len(pages))
        canvas.save()

        text = "\n\n".join("\n".join(page) for page in pages) + "\n"
        (TEXT_DIR / f"{case_name}.txt").write_text(text, encoding="utf-8")
        expected = {
            "case": case_name,
            "documentType": case["type"],
            "pageCount": len(pages),
            "confidence": "High",
            "fields": [
                {
                    "name": name,
                    "value": value,
                    "role": role,
                    "page": page,
                    "confidence": 0.96,
                }
                for name, value, role, page in case["fields"]
            ],
            "candidates": [],
            "findings": [],
        }
        (EXPECTED_DIR / f"{case_name}.json").write_text(
            json.dumps(expected, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )


if __name__ == "__main__":
    generate()
