rootProject.name = "auth-common"

// === APIs (배포 단위) ===
include("services:apis:api-gateway")
include("services:apis:auth-api")

// === Libs (공유 라이브러리) ===
include("services:libs:auth-core")
include("services:libs:auth-infra")
// auth-common-lib → 독립 레포로 분리: github.com/JNU-econovation/econo-passport
