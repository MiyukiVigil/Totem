// node --test browser-extension/
import { test } from "node:test";
import assert from "node:assert/strict";
import { hostOf, matchesSite, siteName } from "./totem.js";

const github = { issuer: "GitHub", icon: "github" };

test("site names ignore subdomains and country suffixes", () => {
  assert.equal(siteName("gist.github.com"), "github");
  assert.equal(siteName("www.bbc.co.uk"), "bbc");
  assert.equal(siteName("github.com.evil.io"), "evil");
  assert.equal(siteName("localhost"), "localhost");
});

test("accounts match their own sites", () => {
  assert.ok(matchesSite(github, "github.com"));
  assert.ok(matchesSite(github, "gist.github.com"));
  assert.ok(matchesSite({ issuer: "Google" }, "accounts.google.com"));
  assert.ok(matchesSite({ issuer: "Microsoft" }, "login.microsoftonline.com"));
  assert.ok(matchesSite({ issuer: "Amazon Web Services" }, "signin.aws.amazon.com"));
  assert.ok(matchesSite({ issuer: "Steam" }, "store.steampowered.com"));
  assert.ok(matchesSite({ issuer: "Twitter" }, "x.com"));
  assert.ok(matchesSite({ issuer: "example.com" }, "login.example.com"));
});

test("look-alike sites don't match", () => {
  assert.ok(!matchesSite(github, "github.com.evil.io"));
  assert.ok(!matchesSite(github, "github-login.com"));
  assert.ok(!matchesSite(github, "githubverify.com"));
  assert.ok(!matchesSite({ issuer: "", label: "me@example.com" }, "example.com"));
  assert.ok(!matchesSite(github, ""));
});

test("only web pages have a host", () => {
  assert.equal(hostOf("https://github.com/login"), "github.com");
  assert.equal(hostOf("chrome://extensions"), "");
  assert.equal(hostOf("not a url"), "");
});
