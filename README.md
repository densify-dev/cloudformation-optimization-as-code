<img src="https://www.densify.com/wp-content/uploads/densify.png" width="300">

# Densify\:\:Optimization\:\:Recommendation 
## CloudFormation Resource Provider
---
The Densify Optimization CloudFormation resource provider allows you to leverage Densify machine learning analytics to enable continuous self-optimization of your cloud resources directly through CloudFormation stack templates. 
This resource provider integrates Densify with AWS CloudFormation and Paramter Store to:
* directly feed approved Densify instance type recommendations to corresponding instances defined in CloudFormation templates;
* update instance details in AWS Parameter Store.
---

## Requirements
- Your AWS infrastructure is provisioned and managed by AWS CloudFormation;
- You have an account with Densify optimizing your AWS infrastructure (click www.densify.com/service/signup for more information about Densify's trial subscription).
 

## Setup
To setup the Densify Optimization CloudFormation resource, follow the steps below.

1. Create a densify-optimization-recommendation-role-stack using the `role-stack.yaml`. This stack will provision two IAM roles (ExecutionRole and LogsAndMetricsDeliveryRole) that contains permissions required for resource operations.
2. Create a log group in CloudWatch for writing logs from the resource provider.
3. Use the following CloudFormation CLI command to register Densify\:\:Optimization\:\:Recommendation with the CloudFormation registry:
```
aws cloudformation register-type 
--type-name Densify::Optimization::Recommendation
--schema-handler-package ${S3ziplink}
--logging-config "{\"LogRoleArn\":\"${LogAndMetricsDeliveryRoleARN}\",\"LogGroupName\": \"${GroupName}\"}"
--type RESOURCE
--role-arn ${ResourceRoleARN}
--region ${yourRegion}
 ```
 Where
 * $\{S3ziplink\} is the Densify public s3 bucket path to the artifact zip file.
 * $\{LogAndMetricsDeliveryRoleARN\} is your LogAndMetricsDeliveryRole IAM Role ARN from step 1.
 * $\{GroupName\} is your log group name, from step 1.
 * $\{yourRegion\} is the resource registration region. You will need to register the resource for each region.
 * ${ResourceRoleARN} is the ARN of the role created in step 1.
 
**Note**: *The `--logging-config` parameter value is in JSON format.*
 
## Prerequisites
1. Your AWS account infrastructure and metrics data are collected and analyzed by Densify.
 
2. Your Densify account connection information is saved in the AWS Parameter Store with the name "**DensifyConnection**". The parameter string for the connection is in JSON format with the following keys:
	```
	{
		"DensifyUrl" : <URL of Densify instance>,
		"DensifyUsername": <Densify user login name>,
		"DensifyPassword": <Densify user password>
	}
	```

	The parameter type for "**DensifyConnection"** can be either String or SecureString. If you use a String type, then the value will be converted to a SecureString after the resource runs for the first time.

	**Note**: *If you decide to use a different name than "**DensifyConnection**" for your connection, then ensure that you specify your custom name in the `DensifyConnectionParameterName` attribute for every resource definition.*
 
3. If you want to force your Densify\:\:Optimization\:\:Recommendation resource to update, create another parameter called "**DensifyRefreshTime**". You can set this parameter to any String value, except the empty String. This parameter enables an instance update, triggered by a new instance type value every time a Stack Update is performed. 

4.  Each instance invoking the Densify\:\:Optimization\:\:Recommendation resource must be uniquely identified by the "Provisioning ID" or "Name" resource tag within the AWS account.

	**Note**: *You will need to perform steps 2 and 3 (create the parameters in the Parameter Store) for every region running the Densify Optimization CloudFormation resource.*

## Usage

Parameter addition to the CloudFormation instance template:
```
    "Parameters": {
        "RefreshTime": {
            "Type": "AWS::SSM::Parameter::Value<String>",
            "Default": "DensifyRefreshTime"
        }
```
Resource definition: 
```
    "Resources": {
        "${InstanceName}Recommendation": {
            "Type": "Densify::Optimization::Recommendation",
            "Properties": {
                "ProvisioningID": "${InstanceName}",
                "InstanceType": "${DefInstanceType}",
                "ForceUpdate": {"Ref":"RefreshTime"}
            }
        }
```		
Invoking the resource provider:
```		
        "${InstanceName}": {
            "Type": "AWS::EC2::Instance",
            "Properties": {
                "InstanceType": {
                    "Fn::GetAtt": [
                        "${InstanceName}Recommendation",
                        "InstanceType"]
                },
				...
			}
				...
		}
 
```
## Inputs 

| Name | Description | Type | Required |
|------|-------------|:----:|:-----:|
| ProvisioningID | The unique instance reference to be optimized by the resource provider. This value must match the provisioned instance tag. | String | Yes |
| InstanceType | The fallback instance type to use if the resource provider fails to retrieve an optimized instance type from Densify. | String | No |
| DensifyConnectionParameterName | The custom Densify connection name in the AWS Parameter Store. This override parameter is used when the Densify connection name is not "DensifyConnection". This property has to be specified for every instance of the resource. | String | No |
| ForceUpdate | This property forces the instance to be updated, as specified by `DensifyRefreshTime`. | String | No |


## Outputs

| Name | Description  |
|------|-------------|
| CurrentType | Current instance size and family. |
| RecommendedType | Densify recommended instance size and family. | 
| SavingsEstimate | The potential monthly savings from modifying the current instance to the Densify recommended instance. | 
| PredictedUptime | The predicted percentage of CPU utilization hours over the duration of a month. |
| InstanceType | The optimal instance size and family. This is either the current type or the Densify recommendation, depending on the automation policy and the approval status. Default value specified in the Densify resource definition will act as fallback when the optimal answer is not available, e.g. the instance is not yet analyzed by Densify. |
| ProvisioningID | The unique identifier for this workload within the AWS account.  Identification is done by the instance name or Provisioning ID tag (if set). |


## Example
 
```
{
    "AWSTemplateFormatVersion": "2010-09-09",
    "Description": "Example usage",
    "Parameters": {
        "RefreshTime": {
            "Type": "AWS::SSM::Parameter::Value<String>",
            "Default": "DensifyRefreshTime"
        }
    },
    "Resources": {
        "EC2eg000Recommendation": {
            "Type": "Densify::Optimization::Recommendation",
            "Properties": {
                "ProvisioningID": "EC2eg000",
                "InstanceType": "m4.large",
                "ForceUpdate": {"Ref":"RefreshTime"}
            }
        },
        "EC2eg000": {
            "Type": "AWS::EC2::Instance",
            "Properties": {
                "ImageId": "ami-00bb1234ccc789d00",
                "InstanceType": {
                    "Fn::GetAtt": [
                        "EC2eg000Recommendation",
                        "InstanceType"
                    ]
                },
                "Tags": [
                    {
                        "Key": "Name",
                        "Value": "EC2eg000"
                    },
                     {
                        "Key": "Provisioning ID",
                        "Value": "EC2eg000"
                    }
                ]
            }
        }
    }
}
```

