#requires -Version 7.0
param([string]$AdminCredentialFile,[string]$BaseUrl='https://127.0.0.1:5177')
$ErrorActionPreference='Stop'
$demoRoot=Split-Path -Parent $PSScriptRoot
$demoPrivate=Join-Path $demoRoot '.local'
$demoBase=$BaseUrl.TrimEnd('/')
if ($demoBase -notmatch '^https://127\.0\.0\.1:[0-9]{1,5}$') { throw 'Demo accounts may only be initialized through local HTTPS.' }
if (-not $AdminCredentialFile) { $AdminCredentialFile=Join-Path $demoPrivate 'approval-admin-credential.xml' }
$demoCredentialFile=Join-Path $demoPrivate 'g5-demo-credentials.xml'
$demoOperationFile=Join-Path $demoPrivate 'g5-demo-operations.json'
$demoCompletedFile=Join-Path $demoPrivate 'g5-demo-created.json'
$demoDocument=Join-Path $demoPrivate '验证账号.md'
$demoDefinitions=@(
    @{Code='student01';Name='验证学生甲';Department='DEMO_CS';Role='STUDENT';RoleName='学生';Enabled=$true},
    @{Code='student02';Name='验证学生乙';Department='DEMO_MATH';Role='STUDENT';RoleName='学生';Enabled=$true},
    @{Code='applicant01';Name='验证整室申请人';Department='DEMO_CS';Role='APPLICANT';RoleName='整室申请人';Enabled=$true},
    @{Code='roomadmin01';Name='验证教室管理员';Department='DEMO_CS';Role='ROOM_ADMIN';RoleName='教室管理员';Enabled=$true},
    @{Code='operator01';Name='验证运营管理员';Department='DEMO_CS';Role='OPERATIONS_ADMIN';RoleName='运营管理员';Enabled=$true},
    @{Code='repairer01';Name='验证维修人员';Department='DEMO_CS';Role='REPAIRER';RoleName='维修人员';Enabled=$true},
    @{Code='disabled01';Name='验证停用学生';Department='DEMO_CS';Role='STUDENT';RoleName='学生';Enabled=$false}
)
New-Item -ItemType Directory -Path $demoPrivate -Force | Out-Null
if (-not (Test-Path -LiteralPath $demoCredentialFile)) {
    $demoGenerated=@($demoDefinitions | ForEach-Object {
        $demoSecure=ConvertTo-SecureString ('Demo-'+[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(12))) -AsPlainText -Force
        [PSCredential]::new($_.Code,$demoSecure)
    })
    $demoGenerated | Export-Clixml -LiteralPath $demoCredentialFile
    Remove-Variable demoGenerated,demoSecure -ErrorAction SilentlyContinue
}
$demoCredentials=@(Import-Clixml -LiteralPath $demoCredentialFile)
foreach ($demoDefinition in $demoDefinitions) {
    if (@($demoCredentials | Where-Object UserName -eq $demoDefinition.Code).Count -ne 1) { throw 'Demo credential collection is incomplete; no account was changed.' }
}
$demoOperations=if(Test-Path -LiteralPath $demoOperationFile){Get-Content -LiteralPath $demoOperationFile -Raw|ConvertFrom-Json -AsHashtable}else{@{}}
$demoAdmin=Import-Clixml -LiteralPath $AdminCredentialFile
$demoLoginBody=@{account=$demoAdmin.UserName;password=$demoAdmin.GetNetworkCredential().Password}|ConvertTo-Json -Compress
$demoLogin=Invoke-WebRequest "$demoBase/api/m2/auth/login" -Method Post -Body $demoLoginBody -ContentType 'application/json' -SkipCertificateCheck -SkipHttpErrorCheck -SessionVariable demoSession -TimeoutSec 15
if($demoLogin.StatusCode -ne 200){throw 'Administrator login failed; no demo data was changed.'}
$demoLoginData=($demoLogin.Content|ConvertFrom-Json).data
$demoCsrf=$demoLoginData.csrfToken
function Invoke-DemoApi([string]$Method,[string]$Path,$Body,[string]$Operation) {
    $demoHeaders=@{}
    if($Method -ne 'GET') {
        if(-not $demoOperations.ContainsKey($Operation)) {
            $demoOperations[$Operation]=[guid]::NewGuid().ToString()
            $demoOperations|ConvertTo-Json|Set-Content -LiteralPath $demoOperationFile -Encoding utf8
        }
        $demoHeaders['Idempotency-Key']=$demoOperations[$Operation]
        $demoHeaders['X-CSRF-Token']=$demoCsrf
    }
    $demoArguments=@{Uri=$demoBase+$Path;Method=$Method;WebSession=$demoSession;Headers=$demoHeaders;SkipCertificateCheck=$true;SkipHttpErrorCheck=$true;TimeoutSec=15}
    if($null -ne $Body){$demoArguments.Body=$Body|ConvertTo-Json -Depth 8 -Compress;$demoArguments.ContentType='application/json'}
    $demoResponse=Invoke-WebRequest @demoArguments
    if($demoResponse.StatusCode -notin @(200,201)) {
        $demoError=try{($demoResponse.Content|ConvertFrom-Json).code}catch{'HTTP_ERROR'}
        throw "Demo API operation failed: $Method $Path ($($demoResponse.StatusCode), $demoError). Credentials were not printed."
    }
    return ($demoResponse.Content|ConvertFrom-Json).data
}
try {
    if(-not(Test-Path -LiteralPath $demoCompletedFile)) {
        $demoRoles=Invoke-DemoApi 'GET' '/api/m2/admin/roles?pageSize=100' $null ''
        $demoRoleIds=@{};foreach($demoRole in $demoRoles.items){$demoRoleIds[$demoRole.key]=$demoRole.id}
        $demoDepartments=@{}
        foreach($demoDepartment in @(@{code='DEMO_CS';name='验证计算机院系'},@{code='DEMO_MATH';name='验证数学院系'})) {
            $demoCreatedDepartment=Invoke-DemoApi 'POST' '/api/m2/admin/departments' $demoDepartment ('department-'+$demoDepartment.code)
            $demoDepartments[$demoDepartment.code]=$demoCreatedDepartment.id
        }
        $demoCreatedAccounts=@()
        foreach($demoDefinition in $demoDefinitions) {
            $demoAccountCredential=$demoCredentials|Where-Object UserName -eq $demoDefinition.Code
            if(-not $demoRoleIds.ContainsKey($demoDefinition.Role)){throw 'Required built-in role not found.'}
            $demoBody=@{userCode=$demoDefinition.Code;displayName=$demoDefinition.Name;departmentId=$demoDepartments[$demoDefinition.Department];email=$demoDefinition.Code+'@example.invalid';initialPassword=$demoAccountCredential.GetNetworkCredential().Password;roleIds=@($demoRoleIds[$demoDefinition.Role])}
            $demoAccount=Invoke-DemoApi 'POST' '/api/m2/admin/accounts' $demoBody ('account-'+$demoDefinition.Code)
            $demoCurrent=Invoke-DemoApi 'GET' ('/api/m2/admin/accounts/'+$demoAccount.id) $null ''
            if(-not $demoDefinition.Enabled -and $demoCurrent.enabled) {
                $null=Invoke-DemoApi 'PATCH' ('/api/m2/admin/accounts/'+$demoAccount.id) @{expectedVersion=$demoCurrent.version;enabled=$false} ('disable-'+$demoDefinition.Code)
            }
            $demoCreatedAccounts+=@{code=$demoDefinition.Code;id=$demoAccount.id}
        }
        foreach($demoDefinition in $demoDefinitions) {
            $demoAccountCredential=$demoCredentials|Where-Object UserName -eq $demoDefinition.Code
            $demoCheckBody=@{account=$demoDefinition.Code;password=$demoAccountCredential.GetNetworkCredential().Password}|ConvertTo-Json -Compress
            $demoCheck=Invoke-WebRequest "$demoBase/api/m2/auth/login" -Method Post -Body $demoCheckBody -ContentType 'application/json' -SkipCertificateCheck -SkipHttpErrorCheck -SessionVariable demoCheckSession -TimeoutSec 15
            if(-not $demoDefinition.Enabled){if($demoCheck.StatusCode -ne 401){throw 'Disabled demo account was not rejected.'};continue}
            if($demoCheck.StatusCode -ne 200){throw 'An enabled demo account could not log in.'}
            $demoCheckData=($demoCheck.Content|ConvertFrom-Json).data
            if($demoCheckData.principal.mustChangePassword){throw 'Unexpected forced password change.'}
            $demoExpectedAction=@{STUDENT='reservation:create';APPLICANT='room:apply';ROOM_ADMIN='approval:level1';OPERATIONS_ADMIN='approval:level2';REPAIRER='workorder:repair'}[$demoDefinition.Role]
            if(-not @($demoCheckData.principal.grants|Where-Object action -eq $demoExpectedAction).Count){throw 'Expected business-role action was not granted.'}
            $demoForbidden=Invoke-WebRequest "$demoBase/api/m2/admin/accounts" -WebSession $demoCheckSession -SkipCertificateCheck -SkipHttpErrorCheck -TimeoutSec 15
            if($demoForbidden.StatusCode -ne 403){throw 'A business-role account unexpectedly has system account management access.'}
            $null=Invoke-RestMethod "$demoBase/api/m2/auth/logout" -Method Post -Body '{}' -ContentType 'application/json' -Headers @{'X-CSRF-Token'=$demoCheckData.csrfToken} -WebSession $demoCheckSession -SkipCertificateCheck -TimeoutSec 15
        }
        @{accounts=$demoCreatedAccounts;departments=$demoDepartments;verifiedAt=(Get-Date).ToString('o')}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $demoCompletedFile -Encoding utf8
        Write-Output 'PASS: seven synthetic accounts created through real HTTPS management APIs; six enabled logins verified and one disabled login rejected.'
    } else { Write-Output 'Existing demo initialization retained. No account, role assignment or password was reset.' }
    $demoLines=@('# 本机验证账号','',"仅用于本机隔离开发验证，网址：$demoBase。此文件含测试密码，位于 Git 忽略目录，不上传仓库。",'','| 用户名 | 初始测试密码 | 角色 | 院系 | 状态 |','| --- | --- | --- | --- | --- |',"| $($demoAdmin.UserName) | $($demoAdmin.GetNetworkCredential().Password) | 系统管理员 | SYSTEM | 启用 |")
    foreach($demoDefinition in $demoDefinitions){$demoAccountCredential=$demoCredentials|Where-Object UserName -eq $demoDefinition.Code;$demoState=if($demoDefinition.Enabled){'启用'}else{'停用（应登录失败）'};$demoLines+="| $($demoDefinition.Code) | $($demoAccountCredential.GetNetworkCredential().Password) | $($demoDefinition.RoleName) | $($demoDefinition.Department) | $demoState |"}
    $demoLines+=@('','账号用途：student01/student02 用于两院系身份对照；applicant01、roomadmin01、operator01、repairer01 用于核对内置业务授权；disabled01 用于验证停用拒绝。','','当前已完成院系/账户/角色/全局参数管理页面。预约、审批、维修业务及房间范围联调仍待相应组实现；业务角色登录成功不代表这些业务已完成。','','所有账号登录后无需强制改密。新设置密码须为 8—24 个字符。表中为初始测试密码，若已自行修改，以新密码为准；重跑初始化不会覆盖修改。','','只有 admin 具备系统账户管理权限，其他业务角色访问账户管理接口应返回 403。角色初始化沿用内置默认范围；ROOM 范围需真实 G4 房间事实提供方后再配置与联调。','','如需再次生成本说明，可运行 scripts/Initialize-G5DemoAccounts.ps1。凭据加密文件和稳定请求键均保存在 .local 内。')
    $demoLines|Set-Content -LiteralPath $demoDocument -Encoding utf8
    Write-Output "Local account document: $demoDocument"
} finally {
    $null=Invoke-RestMethod "$demoBase/api/m2/auth/logout" -Method Post -Body '{}' -ContentType 'application/json' -Headers @{'X-CSRF-Token'=$demoCsrf} -WebSession $demoSession -SkipCertificateCheck -TimeoutSec 15
}
