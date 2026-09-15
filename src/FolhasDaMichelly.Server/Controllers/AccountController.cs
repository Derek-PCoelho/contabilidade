using System.Net;
using System.Security.Claims;
using FolhasDaMichelly.Contracts.Identity;
using FolhasDaMichelly.Domain.Identity;
using FolhasDaMichelly.Infrastructure.Identity;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace FolhasDaMichelly.Server.Controllers;

[Route("account")]
[EnableRateLimiting("authentication")]
public sealed class AccountController(
    UserManager<ApplicationUser> userManager,
    SignInManager<ApplicationUser> signInManager,
    IAntiforgery antiforgery) : Controller
{
    [HttpGet("login")]
    public IActionResult Login([FromQuery] string? returnUrl = null, [FromQuery] bool requiresMfa = false)
    {
        var tokens = antiforgery.GetAndStoreTokens(HttpContext);
        var safeReturnUrl = Url.IsLocalUrl(returnUrl) ? returnUrl : "/";
        return Content(
            RenderLoginForm(tokens.FormFieldName, tokens.RequestToken, safeReturnUrl, requiresMfa),
            "text/html; charset=utf-8");
    }

    [HttpPost("login")]
    [ValidateAntiForgeryToken]
    public async Task<IActionResult> Login(
        [FromForm] string email,
        [FromForm] string password,
        [FromForm] string? authenticatorCode,
        [FromForm] string? returnUrl)
    {
        var user = await userManager.FindByEmailAsync(email.Trim());
        if (user is null || !user.IsActive || !user.EmailConfirmed)
        {
            return Unauthorized();
        }

        var passwordResult = await signInManager.CheckPasswordSignInAsync(
            user,
            password,
            lockoutOnFailure: true);
        if (!passwordResult.Succeeded)
        {
            return Unauthorized();
        }

        var roles = await userManager.GetRolesAsync(user);
        var privileged = roles.Contains(AppRoles.OwnerTechnical, StringComparer.Ordinal) ||
            roles.Contains(AppRoles.Administrator, StringComparer.Ordinal) ||
            roles.Contains(AppRoles.Manager, StringComparer.Ordinal);
        var additionalClaims = new List<Claim>
        {
            new(AppClaimNames.AuthenticationMethodReference, "pwd"),
        };

        if (privileged)
        {
            if (!user.TwoFactorEnabled)
            {
                return Problem(
                    "Privileged users must enroll an authenticator before signing in.",
                    statusCode: StatusCodes.Status403Forbidden);
            }

            if (string.IsNullOrWhiteSpace(authenticatorCode))
            {
                return RedirectToAction(
                    nameof(Login),
                    new { returnUrl, requiresMfa = true });
            }

            var validCode = await userManager.VerifyTwoFactorTokenAsync(
                user,
                TokenOptions.DefaultAuthenticatorProvider,
                authenticatorCode.Replace(" ", string.Empty, StringComparison.Ordinal));
            if (!validCode)
            {
                await userManager.AccessFailedAsync(user);
                return Unauthorized();
            }

            await userManager.ResetAccessFailedCountAsync(user);
            additionalClaims.Add(new Claim(AppClaimNames.AuthenticationMethodReference, "mfa"));
        }

        await signInManager.SignInWithClaimsAsync(
            user,
            isPersistent: false,
            additionalClaims);
        user.LastAccessAtUtc = DateTimeOffset.UtcNow;
        await userManager.UpdateAsync(user);

        return LocalRedirect(Url.IsLocalUrl(returnUrl) ? returnUrl : "/");
    }

    private static string RenderLoginForm(
        string fieldName,
        string? token,
        string returnUrl,
        bool requiresMfa)
    {
        var encodedReturnUrl = WebUtility.HtmlEncode(returnUrl);
        var encodedToken = WebUtility.HtmlEncode(token ?? string.Empty);
        var mfaField = requiresMfa
            ? "<label>Código do autenticador<input name=\"authenticatorCode\" autocomplete=\"one-time-code\" required></label>"
            : string.Empty;
        return $$"""
            <!doctype html>
            <html lang="pt-BR">
            <head><meta charset="utf-8"><title>Entrar — Folhas da Michelly</title></head>
            <body>
              <main>
                <h1>Folhas da Michelly</h1>
                <p>Autenticação segura do aplicativo.</p>
                <form method="post" action="/account/login">
                  <input type="hidden" name="{{WebUtility.HtmlEncode(fieldName)}}" value="{{encodedToken}}">
                  <input type="hidden" name="returnUrl" value="{{encodedReturnUrl}}">
                  <label>E-mail<input type="email" name="email" autocomplete="username" required></label>
                  <label>Senha<input type="password" name="password" autocomplete="current-password" required></label>
                  {{mfaField}}
                  <button type="submit">Entrar</button>
                </form>
              </main>
            </body>
            </html>
            """;
    }
}
