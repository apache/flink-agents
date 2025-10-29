package org.apache.flink.agents.integration.test;

import org.apache.flink.agents.api.AgentsExecutionEnvironment;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class AgentWithAzureAIExample {
    /** Runs the example pipeline. */
    public static void main(String[] args) throws Exception {
        // Create the execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        // Use prompts that trigger different tool calls in the agent
        DataStream<String> inputStream =
                env.fromData(
                        "Convert 25 degrees Celsius to Fahrenheit",
                        "What is 98.6 Fahrenheit in Celsius?",
                        "Change 32 degrees Celsius to Fahrenheit",
                        "If it's 75 degrees Fahrenheit, what would that be in Celsius?",
                        "Convert room temperature of 20C to F",
                        "Calculate BMI for someone who is 1.75 meters tall and weighs 70 kg",
                        "What's the BMI for a person weighing 85 kg with height 1.80 meters?",
                        "Can you tell me the BMI if I'm 1.65m tall and weigh 60kg?",
                        "Find BMI for 75kg weight and 1.78m height",
                        "Create me a random number please");

        // Create agents execution environment
        AgentsExecutionEnvironment agentsEnv =
                AgentsExecutionEnvironment.getExecutionEnvironment(env);

        // Apply agent to the DataStream and use the prompt itself as the key
        DataStream<Object> outputStream =
                agentsEnv
                        .fromDataStream(inputStream, (KeySelector<String, String>) value -> value)
                        .apply(new AgentWithAzureAI())
                        .toDataStream();

        // Print the results
        outputStream.print();

        // Execute the pipeline
        agentsEnv.execute();
    }
}
