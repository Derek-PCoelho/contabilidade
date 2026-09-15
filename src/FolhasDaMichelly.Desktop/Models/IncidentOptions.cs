using FolhasDaMichelly.Application.Incidents;

namespace FolhasDaMichelly.Desktop.Models;

public sealed record IncidentCategoryOption(IncidentCategory Value, string Label);

public sealed record IncidentSeverityOption(IncidentSeverity Value, string Label);

public sealed record IncidentStatusOption(IncidentStatus Value, string Label);
