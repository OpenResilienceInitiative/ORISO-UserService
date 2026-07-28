#!/usr/bin/env bash

java_major_version() {
  local java_command="$1"
  local version_output
  version_output="$("${java_command}" -version 2>&1 | head -n 1)" || return 1
  if [[ "${version_output}" =~ \"([0-9]+)(\.([0-9]+))? ]]; then
    if [[ "${BASH_REMATCH[1]}" == "1" ]]; then
      printf '%s\n' "${BASH_REMATCH[3]}"
    else
      printf '%s\n' "${BASH_REMATCH[1]}"
    fi
    return 0
  fi
  return 1
}

ensure_java_21() {
  local current_java
  local current_major=""
  local current_version="unavailable"
  current_java="$(command -v java 2>/dev/null || true)"
  if [[ -n "${current_java}" ]]; then
    current_major="$(java_major_version "${current_java}" || true)"
    current_version="$("${current_java}" -version 2>&1 | head -n 1)"
  fi
  if [[ "${current_major}" == "21" ]]; then
    return 0
  fi

  local resolver="${JAVA_21_HOME_RESOLVER:-}"
  if [[ -z "${resolver}" && -x /usr/libexec/java_home ]]; then
    resolver="/usr/libexec/java_home"
  fi
  if [[ -n "${resolver}" && -x "${resolver}" ]]; then
    local java_21_home
    java_21_home="$("${resolver}" -v 21 2>/dev/null || true)"
    if [[ -x "${java_21_home}/bin/java" ]] &&
      [[ "$(java_major_version "${java_21_home}/bin/java" || true)" == "21" ]]; then
      export JAVA_HOME="${java_21_home}"
      export PATH="${JAVA_HOME}/bin:${PATH}"
      return 0
    fi
  fi

  echo "UserService requires Java 21; current runtime: ${current_version}" >&2
  return 1
}
